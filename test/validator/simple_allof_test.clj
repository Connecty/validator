(ns validator.simple-allof-test
  (:require [clojure.test :refer :all]
            [validator.openapi :as validator]))

(deftest test-simple-allof-with-self-reference
  (testing "Simple allOf with self-reference"
    (let [components {:Node {:allOf [{:type "object"
                                      :required ["id"]
                                      :properties {:id {:type "string"}}}
                                     {:type "object"
                                      :properties {:child {:$ref "#/components/schemas/Node"}}}]}}

          test-data {:id "root" :child {:id "child"}}
          invalid-data {:child {:id "child"}}] ; missing required id

      (is (boolean? (validator/validate-data {:$ref "#/components/schemas/Node"} test-data components)))
      (is (false? (validator/validate-data {:$ref "#/components/schemas/Node"} invalid-data components))))))

(deftest test-expand-self-reference-with-allof
  (testing "expand-self-reference handles allOf"
    (let [schema {:allOf [{:type "object" :properties {:id {:type "string"}}}
                          {:type "object" :properties {:self {:$ref "#/components/schemas/Test"}}}]}
          expanded (validator/expand-self-reference :Test schema)]

      (is (not (contains? expanded :allOf))) ; allOf should be merged
      (is (= "object" (:type expanded)))
      (is (contains? (:properties expanded) :id))
      (is (contains? (:properties expanded) :self)))))

(deftest test-allof-circular-expansion
  (testing "expand-circular-references with allOf"
    (let [components {:TreeNode {:allOf [{:type "object"
                                          :required ["id"]
                                          :properties {:id {:type "string"}}}
                                         {:type "object"
                                          :properties {:children {:type "array"
                                                                  :items {:$ref "#/components/schemas/TreeNode"}}}}]}}

          expanded (validator/expand-circular-references components)
          expanded-node (get expanded :TreeNode)]

      (is (not (contains? expanded-node :allOf))) ; allOf should be merged
      (is (= "object" (:type expanded-node)))
      (is (some? expanded-node)))))

(deftest test-allof-validation-correctness
  (testing "Validation works correctly with allOf and self-reference"
    (let [components {:User {:allOf [{:type "object"
                                      :required ["name"]
                                      :properties {:name {:type "string"}}}
                                     {:type "object"
                                      :properties {:friend {:$ref "#/components/schemas/User"}}}]}}

          valid-data {:name "John" :friend {:name "Jane"}}
          invalid-data {:friend {:name "Jane"}}] ; missing required name

      (is (true? (validator/validate-data {:$ref "#/components/schemas/User"} valid-data components)))
      (is (false? (validator/validate-data {:$ref "#/components/schemas/User"} invalid-data components))))))
