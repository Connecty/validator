(ns validator.openapi
  "OpenAPI 3.1 schema validation using Malli"
  (:require [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]))

;; OpenAPI 3.1のスキーマをMalliスキーマに変換するヘルパー関数

(defn- parse-type
  "OpenAPI typeとformatからMalliのスキーマタイプを決定"
  [openapi-type format]
  (case openapi-type
    "string" (case format
               "date" :string
               "date-time" :string
               "email" :string
               "uuid" :string
               :string)
    "integer" (case format
                "int32" :int
                "int64" :int
                :int)
    "number" (case format
               "float" :double
               "double" :double
               :double)
    "boolean" :boolean
    "array" :sequential
    "object" :map
    :any))

(defn- convert-string-constraints
  "文字列の制約をMalliの制約マップに変換"
  [schema]
  (cond-> {}
    (:minLength schema) (assoc :min (:minLength schema))
    (:maxLength schema) (assoc :max (:maxLength schema))
    (:pattern schema) (assoc :pattern (re-pattern (:pattern schema)))))

(defn- convert-number-constraints
  "数値の制約をMalliの制約マップに変換"
  [schema]
  (cond-> {}
    (:minimum schema) (assoc :min (:minimum schema))
    (:maximum schema) (assoc :max (:maximum schema))
    (:exclusiveMinimum schema) (assoc :min (:exclusiveMinimum schema))
    (:exclusiveMaximum schema) (assoc :max (:exclusiveMaximum schema))))

(defn- convert-array-constraints
  "配列の制約をMalliの制約マップに変換"
  [schema]
  (cond-> {}
    (:minItems schema) (assoc :min (:minItems schema))
    (:maxItems schema) (assoc :max (:maxItems schema))))

(declare openapi-schema->malli)

(defn- convert-object-properties-with-registry
  "オブジェクトのプロパティをMalliの形式に変換（レジストリ付き）"
  [properties required registry visiting]
  (reduce-kv
   (fn [acc prop-name prop-schema]
     (let [malli-schema (openapi-schema->malli prop-schema registry visiting)
           required? (contains? (set required) (name prop-name))
           key-name (keyword prop-name)]
       (if required?
         (conj acc [key-name malli-schema])
         (conj acc [key-name {:optional true} malli-schema]))))
   []
   properties))

(defn openapi-schema->malli
  "OpenAPI 3.1スキーマをMalliスキーマに変換"
  ([schema]
   (openapi-schema->malli schema {} #{}))
  ([schema registry]
   (openapi-schema->malli schema registry #{}))
  ([schema registry visiting]
   (cond
     ;; 参照の場合
     (:$ref schema)
     (let [ref-path (:$ref schema)
           ref-key (if (string? ref-path)
                     (keyword (last (str/split ref-path #"/")))
                     ref-path)]
       (if (contains? visiting ref-key)
         ;; 循環参照を検出した場合、Malliの:refを使用
         [:ref ref-key]
         (if-let [resolved-schema (get registry ref-key)]
           (openapi-schema->malli resolved-schema registry (conj visiting ref-key))
           [:ref ref-key])))

     ;; anyOf / oneOf / allOf
     (:anyOf schema)
     (into [:or] (map #(openapi-schema->malli % registry visiting) (:anyOf schema)))

     (:oneOf schema)
     (into [:or] (map #(openapi-schema->malli % registry visiting) (:oneOf schema)))

     (:allOf schema)
     (into [:and] (map #(openapi-schema->malli % registry visiting) (:allOf schema)))

     ;; 基本的な型の変換
     :else
     (let [base-type (parse-type (:type schema) (:format schema))
           constraints (case (:type schema)
                         "string" (convert-string-constraints schema)
                         ("integer" "number") (convert-number-constraints schema)
                         "array" (convert-array-constraints schema)
                         {})]
       (case (:type schema)
         "array"
         (let [items-schema (when (:items schema)
                              (openapi-schema->malli (:items schema) registry visiting))
               base-schema (if items-schema
                             [:vector items-schema]
                             [:vector :any])]
           (if (empty? constraints)
             base-schema
             (into [(first base-schema) constraints] (rest base-schema))))

         "object"
         (if (:properties schema)
           (let [properties (convert-object-properties-with-registry
                             (:properties schema)
                             (or (:required schema) [])
                             registry
                             visiting)]
             (into [:map] properties))
           [:map])

         ;; その他の型
         (if (empty? constraints)
           base-type
           [base-type constraints]))))))

(defn expand-self-reference
  "自己参照を実際のスキーマ内容で置換する"
  [schema-name schema]
  (letfn [(expand-ref [s original-schema]
            (cond
              (map? s)
              (reduce-kv
               (fn [acc k v]
                 (if (and (= k :$ref)
                          (= v (str "#/components/schemas/" (name schema-name))))
                   ;; 自己参照を実際のスキーマ内容で置換（循環参照するプロパティは除外）
                   (let [safe-schema (get-safe-schema original-schema schema-name)]
                     (merge (dissoc acc :$ref) safe-schema))
                   (assoc acc k (expand-ref v original-schema))))
               {}
               s)

              (vector? s)
              (mapv #(expand-ref % original-schema) s)

              :else s))

          (get-safe-schema [schema schema-name]
            (cond
              ;; allOfスキーマの場合：allOf内のスキーマから情報を収集
              (:allOf schema)
              (let [merged-props (reduce (fn [acc item]
                                           (if (:properties item)
                                             (merge acc (:properties item))
                                             acc))
                                         {}
                                         (:allOf schema))
                    merged-required (reduce (fn [acc item]
                                              (if (:required item)
                                                (into acc (:required item))
                                                acc))
                                            []
                                            (:allOf schema))
                    ;; 循環参照するプロパティを除外
                    safe-props (reduce-kv
                                (fn [props prop-k prop-v]
                                  (if (self-referencing? prop-v schema-name)
                                    props
                                    (assoc props prop-k (expand-ref prop-v schema))))
                                {}
                                merged-props)]
                {:type "object"
                 :required merged-required
                 :properties safe-props})

              ;; oneOfスキーマの場合：共通プロパティを収集
              (:oneOf schema)
              (let [common-props (reduce (fn [acc item]
                                           (if (:properties item)
                                             (merge acc (:properties item))
                                             acc))
                                         {}
                                         (:oneOf schema))
                    common-required (reduce (fn [acc item]
                                              (if (:required item)
                                                (into acc (:required item))
                                                acc))
                                            []
                                            (:oneOf schema))
                    ;; 循環参照するプロパティを除外
                    safe-props (reduce-kv
                                (fn [props prop-k prop-v]
                                  (if (self-referencing? prop-v schema-name)
                                    props
                                    (assoc props prop-k (expand-ref prop-v schema))))
                                {}
                                common-props)]
                {:type "object"
                 :required common-required
                 :properties safe-props})

              ;; anyOfスキーマの場合：共通プロパティを収集
              (:anyOf schema)
              (let [common-props (reduce (fn [acc item]
                                           (if (:properties item)
                                             (merge acc (:properties item))
                                             acc))
                                         {}
                                         (:anyOf schema))
                    common-required (reduce (fn [acc item]
                                              (if (:required item)
                                                (into acc (:required item))
                                                acc))
                                            []
                                            (:anyOf schema))
                    ;; 循環参照するプロパティを除外
                    safe-props (reduce-kv
                                (fn [props prop-k prop-v]
                                  (if (self-referencing? prop-v schema-name)
                                    props
                                    (assoc props prop-k (expand-ref prop-v schema))))
                                {}
                                common-props)]
                {:type "object"
                 :required common-required
                 :properties safe-props})

              ;; 通常のスキーマの場合
              :else
              (-> schema
                  (select-keys [:type :required :properties :additionalProperties])
                  (update :properties #(when %
                                         (reduce-kv
                                          (fn [props prop-k prop-v]
                                            (if (self-referencing? prop-v schema-name)
                                              props
                                              (assoc props prop-k (expand-ref prop-v schema))))
                                          {}
                                          %))))))

          (self-referencing? [value schema-name]
            (let [self-ref-str (str "#/components/schemas/" (name schema-name))]
              (cond
                (and (map? value) (= (:$ref value) self-ref-str))
                true

                (and (map? value)
                     (= "array" (:type value))
                     (= (get-in value [:items :$ref]) self-ref-str))
                true

                :else false)))]
    (expand-ref schema schema)))

(defn expand-circular-references
  "循環参照を展開してより単純な構造に変換"
  [components-schemas]
  (reduce-kv
   (fn [acc schema-name schema]
     (assoc acc schema-name (expand-self-reference schema-name schema)))
   {}
   components-schemas))

(defn create-registry
  "OpenAPIコンポーネントスキーマからMalliレジストリを作成"
  [components-schemas]
  (reduce-kv
   (fn [registry ref-name schema]
     (let [ref-key (keyword (name ref-name))]
       (assoc registry ref-key schema)))
   {}
   components-schemas))

(defn create-validator
  "OpenAPI 3.1スキーマからMalliバリデーターを作成"
  ([openapi-schema]
   (create-validator openapi-schema {}))
  ([openapi-schema registry]
   (let [expanded-registry (expand-circular-references registry)
         components-registry (create-registry expanded-registry)
         full-registry (merge (m/default-schemas)
                              (reduce-kv (fn [acc k v]
                                           (assoc acc k (openapi-schema->malli v components-registry)))
                                         {} components-registry))
         malli-schema (openapi-schema->malli openapi-schema components-registry)]
     (if (and (vector? malli-schema) (= :ref (first malli-schema)))
       (m/validator malli-schema {:registry full-registry})
       (m/validator malli-schema)))))

(defn create-explainer
  "OpenAPI 3.1スキーマからMalliエクスプレイナーを作成"
  ([openapi-schema]
   (create-explainer openapi-schema {}))
  ([openapi-schema registry]
   (let [expanded-registry (expand-circular-references registry)
         components-registry (create-registry expanded-registry)
         full-registry (merge (m/default-schemas)
                              (reduce-kv (fn [acc k v]
                                           (assoc acc k (openapi-schema->malli v components-registry)))
                                         {} components-registry))
         malli-schema (openapi-schema->malli openapi-schema components-registry)]
     (if (and (vector? malli-schema) (= :ref (first malli-schema)))
       (m/explainer malli-schema {:registry full-registry})
       (m/explainer malli-schema)))))

(defn validate-data
  "データをOpenAPI 3.1スキーマでバリデーション"
  ([openapi-schema data]
   (validate-data openapi-schema data {}))
  ([openapi-schema data registry]
   (let [validator (create-validator openapi-schema registry)]
     (validator data))))

(defn explain-errors
  "バリデーションエラーの詳細を取得"
  ([openapi-schema data]
   (explain-errors openapi-schema data {}))
  ([openapi-schema data registry]
   (let [explainer (create-explainer openapi-schema registry)
         explanation (explainer data)]
     (when explanation
       (me/humanize explanation)))))

(defn validate-with-errors
  "バリデーションを実行し、エラーがある場合は人間が読める形式で返す"
  ([openapi-schema data]
   (validate-with-errors openapi-schema data {}))
  ([openapi-schema data registry]
   (let [valid? (validate-data openapi-schema data registry)]
     (if valid?
       {:valid? true :data data}
       {:valid? false
        :errors (explain-errors openapi-schema data registry)}))))

(defn example-user-schema
  "ユーザースキーマの例"
  []
  {:type "object"
   :required ["id" "name" "email"]
   :properties
   {:id {:type "integer" :minimum 1}
    :name {:type "string" :minLength 1 :maxLength 100}
    :email {:type "string" :format "email"}
    :age {:type "integer" :minimum 0 :maximum 150}
    :active {:type "boolean"}}})

(defn example-components-schemas
  "コンポーネントスキーマの例"
  []
  {:User {:type "object"
          :required ["id" "name"]
          :properties
          {:id {:type "integer" :minimum 1}
           :name {:type "string" :minLength 1}
           :email {:type "string" :format "email"}}}

   :Address {:type "object"
             :required ["street" "city"]
             :properties
             {:street {:type "string" :minLength 1}
              :city {:type "string" :minLength 1}
              :zipCode {:type "string" :pattern "^[0-9]{5}(-[0-9]{4})?$"}}}

   :UserWithAddress {:type "object"
                     :required ["user" "address"]
                     :properties
                     {:user {:$ref "#/components/schemas/User"}
                      :address {:$ref "#/components/schemas/Address"}
                      :isDefault {:type "boolean"}}}})
