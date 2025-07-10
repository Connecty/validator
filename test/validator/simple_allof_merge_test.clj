(ns validator.simple-allof-merge-test
  (:require [clojure.test :refer :all]))

(defn merge-allof-schemas
  "Simple allOf merging - merge schemas in order"
  [allof-schemas]
  (reduce (fn [acc schema]
            (cond
              ;; Both are objects with properties
              (and (:properties acc) (:properties schema))
              (-> acc
                  (update :properties merge (:properties schema))
                  (update :required #(into (vec (or % [])) (or (:required schema) [])))
                  (merge (dissoc schema :properties :required)))

              ;; First schema is empty/nil
              (empty? acc)
              schema

              ;; Default merge
              :else
              (merge acc schema)))
          {}
          allof-schemas))

(defn expand-allof-simple
  "Simple allOf expansion by merging schemas"
  [schema]
  (if (:allOf schema)
    (let [merged (merge-allof-schemas (:allOf schema))]
      (merge (dissoc schema :allOf) merged))
    schema))

(deftest test-simple-allof-merge
  (testing "Basic allOf merging"
    (let [schema {:allOf [{:type "object"
                           :required ["id"]
                           :properties {:id {:type "string"}}}
                          {:type "object"
                           :properties {:name {:type "string"}}}]}
          merged (expand-allof-simple schema)]

      (is (= "object" (:type merged)))
      (is (= ["id"] (:required merged)))
      (is (= {:id {:type "string"} :name {:type "string"}} (:properties merged)))
      (is (not (contains? merged :allOf)))))

  (testing "allOf with overlapping required fields"
    (let [schema {:allOf [{:type "object"
                           :required ["id" "name"]
                           :properties {:id {:type "string"}}}
                          {:type "object"
                           :required ["name" "email"]
                           :properties {:name {:type "string"}
                                        :email {:type "string"}}}]}
          merged (expand-allof-simple schema)]

      (is (= ["id" "name" "name" "email"] (:required merged))) ; duplicates preserved
      (is (= 3 (count (:properties merged))))))

  (testing "allOf with self-reference"
    (let [schema {:allOf [{:type "object"
                           :required ["id"]
                           :properties {:id {:type "string"}}}
                          {:type "object"
                           :properties {:children {:type "array"
                                                   :items {:$ref "#/components/schemas/Node"}}
                                        :parent {:$ref "#/components/schemas/Node"}}}]}
          merged (expand-allof-simple schema)]

      (is (= "object" (:type merged)))
      (is (= ["id"] (:required merged)))
      (is (= {:$ref "#/components/schemas/Node"}
             (get-in merged [:properties :parent])))
      (is (= {:$ref "#/components/schemas/Node"}
             (get-in merged [:properties :children :items])))))

  (testing "nested allOf"
    (let [schema {:allOf [{:type "object"
                           :properties {:basic {:type "string"}}}
                          {:allOf [{:type "object"
                                    :properties {:nested1 {:type "string"}}}
                                   {:type "object"
                                    :properties {:nested2 {:type "string"}}}]}]}

          ;; First expand inner allOf
          inner-expanded (update schema :allOf
                                 (fn [schemas]
                                   (map expand-allof-simple schemas)))
          ;; Then expand outer allOf
          fully-expanded (expand-allof-simple inner-expanded)]

      (is (= "object" (:type fully-expanded)))
      (is (contains? (:properties fully-expanded) :basic))
      (is (contains? (:properties fully-expanded) :nested1))
      (is (contains? (:properties fully-expanded) :nested2)))))

(deftest test-allof-preserves-references
  (testing "allOf preserves $ref without modification"
    (let [complex-schema {:allOf [{:$ref "#/components/schemas/BaseNode"}
                                  {:type "object"
                                   :properties {:children {:type "array"
                                                           :items {:$ref "#/components/schemas/Node"}}
                                                :metadata {:type "object"
                                                           :properties {:parent {:$ref "#/components/schemas/Node"}}}}}]}
          merged (expand-allof-simple complex-schema)]

      ;; Should preserve all references as-is
      (is (= {:$ref "#/components/schemas/BaseNode"} (first (:allOf complex-schema))))
      (is (= {:$ref "#/components/schemas/Node"}
             (get-in merged [:properties :children :items])))
      (is (= {:$ref "#/components/schemas/Node"}
             (get-in merged [:properties :metadata :properties :parent]))))))

(deftest test-comparison-with-current-expand-self-reference
  (testing "Compare simple merge vs current expand-self-reference"
    (let [schema {:allOf [{:type "object"
                           :required ["id"]
                           :properties {:id {:type "string"}}}
                          {:type "object"
                           :properties {:self-ref {:$ref "#/components/schemas/TestNode"}}}]}

          ;; Simple merge approach
          simple-result (expand-allof-simple schema)

          ;; What we expect vs what current function might produce
          expected-properties {:id {:type "string"}
                               :self-ref {:$ref "#/components/schemas/TestNode"}}]

      (is (= expected-properties (:properties simple-result)))
      (is (= "object" (:type simple-result)))
      (is (= ["id"] (:required simple-result)))

      ;; The reference should be preserved, not turned into {:properties {}}
      (is (not= {:properties {}} (get-in simple-result [:properties :self-ref]))))))

(deftest test-edge-cases
  (testing "Empty allOf"
    (let [schema {:allOf []}
          merged (expand-allof-simple schema)]
      (is (= {} merged))))

  (testing "Single item allOf"
    (let [schema {:allOf [{:type "object" :properties {:id {:type "string"}}}]}
          merged (expand-allof-simple schema)]
      (is (= {:type "object" :properties {:id {:type "string"}}} merged))))

  (testing "allOf with non-object schemas"
    (let [schema {:allOf [{:type "string"} {:maxLength 10}]}
          merged (expand-allof-simple schema)]
      (is (= {:type "string" :maxLength 10} merged)))))
