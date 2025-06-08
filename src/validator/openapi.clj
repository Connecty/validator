(ns validator.openapi
  "OpenAPI 3.1 schema validation using Malli"
  (:require [malli.core :as m]
            [malli.error :as me]))

;; OpenAPI 3.1のスキーマをMalliスキーマに変換するヘルパー関数

(defn- parse-type [openapi-type format]
  "OpenAPI typeとformatからMalliのスキーマタイプを決定"
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

(defn- convert-string-constraints [schema]
  "文字列の制約をMalliの制約マップに変換"
  (cond-> {}
    (:minLength schema) (assoc :min (:minLength schema))
    (:maxLength schema) (assoc :max (:maxLength schema))
    (:pattern schema) (assoc :pattern (re-pattern (:pattern schema)))))

(defn- convert-number-constraints [schema]
  "数値の制約をMalliの制約マップに変換"
  (cond-> {}
    (:minimum schema) (assoc :min (:minimum schema))
    (:maximum schema) (assoc :max (:maximum schema))
    (:exclusiveMinimum schema) (assoc :min (:exclusiveMinimum schema))
    (:exclusiveMaximum schema) (assoc :max (:exclusiveMaximum schema))))

(defn- convert-array-constraints [schema]
  "配列の制約をMalliの制約マップに変換"
  (cond-> {}
    (:minItems schema) (assoc :min (:minItems schema))
    (:maxItems schema) (assoc :max (:maxItems schema))))

(declare openapi-schema->malli)

(defn- convert-object-properties [properties required]
  "オブジェクトのプロパティをMalliの形式に変換"
  (reduce-kv
   (fn [acc prop-name prop-schema]
     (let [malli-schema (openapi-schema->malli prop-schema)
           required? (contains? (set required) (name prop-name))
           key-name (keyword prop-name)]
       (if required?
         (conj acc [key-name malli-schema])
         (conj acc [key-name {:optional true} malli-schema]))))
   []
   properties))

(defn openapi-schema->malli
  "OpenAPI 3.1スキーマをMalliスキーマに変換"
  [schema]
  (cond
    ;; 参照の場合
    (:$ref schema)
    [:ref (:$ref schema)]
    
    ;; anyOf / oneOf / allOf
    (:anyOf schema)
    (into [:or] (map openapi-schema->malli (:anyOf schema)))
    
    (:oneOf schema)
    (into [:or] (map openapi-schema->malli (:oneOf schema)))
    
    (:allOf schema)
    (into [:and] (map openapi-schema->malli (:allOf schema)))
    
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
                            (openapi-schema->malli (:items schema)))
              base-schema (if items-schema
                            [:vector items-schema]
                            [:vector :any])]
          (if (empty? constraints)
            base-schema
            (into [(first base-schema) constraints] (rest base-schema))))
        
        "object"
        (if (:properties schema)
          (let [properties (convert-object-properties 
                           (:properties schema) 
                           (or (:required schema) []))]
            (into [:map] properties))
          [:map])
        
        ;; その他の型
        (if (empty? constraints)
          base-type
          [base-type constraints])))))

(defn create-validator
  "OpenAPI 3.1スキーマからMalliバリデーターを作成"
  [openapi-schema]
  (let [malli-schema (openapi-schema->malli openapi-schema)]
    (m/validator malli-schema)))

(defn create-explainer
  "OpenAPI 3.1スキーマからMalliエクスプレイナーを作成"
  [openapi-schema]
  (let [malli-schema (openapi-schema->malli openapi-schema)]
    (m/explainer malli-schema)))

(defn validate-data
  "データをOpenAPI 3.1スキーマでバリデーション"
  [openapi-schema data]
  (let [validator (create-validator openapi-schema)]
    (validator data)))

(defn explain-errors
  "バリデーションエラーの詳細を取得"
  [openapi-schema data]
  (let [explainer (create-explainer openapi-schema)
        explanation (explainer data)]
    (when explanation
      (me/humanize explanation))))

(defn validate-with-errors
  "バリデーションを実行し、エラーがある場合は人間が読める形式で返す"
  [openapi-schema data]
  (let [valid? (validate-data openapi-schema data)]
    (if valid?
      {:valid? true :data data}
      {:valid? false 
       :errors (explain-errors openapi-schema data)})))

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