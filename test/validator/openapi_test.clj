(ns validator.openapi-test
  (:require [clojure.test :refer :all]
            [validator.openapi :as validator]))

(deftest test-basic-string-validation
  (testing "Basic string validation"
    (let [schema {:type "string" :minLength 3 :maxLength 10}]
      (is (true? (validator/validate-data schema "hello")))
      (is (false? (validator/validate-data schema "hi")))
      (is (false? (validator/validate-data schema "this is too long"))))))

(deftest test-integer-validation
  (testing "Integer validation with constraints"
    (let [schema {:type "integer" :minimum 1 :maximum 100}]
      (is (true? (validator/validate-data schema 50)))
      (is (false? (validator/validate-data schema 0)))
      (is (false? (validator/validate-data schema 101))))))

(deftest test-object-validation
  (testing "Object validation with required fields"
    (let [schema {:type "object"
                  :required ["name" "age"]
                  :properties
                  {:name {:type "string" :minLength 1}
                   :age {:type "integer" :minimum 0}
                   :email {:type "string"}}}]
      
      (is (true? (validator/validate-data schema 
                                         {:name "John" :age 30})))
      
      (is (true? (validator/validate-data schema 
                                         {:name "John" :age 30 :email "john@example.com"})))
      
      (is (false? (validator/validate-data schema 
                                          {:name "John"}))) ; missing age
      
      (is (false? (validator/validate-data schema 
                                          {:name "" :age 30}))) ; empty name
      
      (is (false? (validator/validate-data schema 
                                          {:name "John" :age -1})))))) ; negative age

(deftest test-array-validation
  (testing "Array validation"
    (let [schema {:type "array"
                  :items {:type "integer"}
                  :minItems 1
                  :maxItems 5}]
      
      (is (true? (validator/validate-data schema [1 2 3])))
      (is (false? (validator/validate-data schema []))) ; too few items
      (is (false? (validator/validate-data schema [1 2 3 4 5 6]))) ; too many items
      (is (false? (validator/validate-data schema [1 "2" 3]))))))

(deftest test-validation-with-errors
  (testing "Validation with error messages"
    (let [schema {:type "object"
                  :required ["name"]
                  :properties {:name {:type "string" :minLength 3}}}
          invalid-data {:name "Hi"}
          result (validator/validate-with-errors schema invalid-data)]
      
      (is (false? (:valid? result)))
      (is (some? (:errors result))))))

(deftest test-anyof-validation
  (testing "anyOf validation"
    (let [schema {:anyOf [{:type "string"}
                          {:type "integer"}]}]
      
      (is (true? (validator/validate-data schema "hello")))
      (is (true? (validator/validate-data schema 42)))
      (is (false? (validator/validate-data schema []))))))

(deftest test-user-schema-example
  (testing "User schema example"
    (let [schema (validator/example-user-schema)
          valid-user {:id 1 :name "John Doe" :email "john@example.com" :age 30 :active true}
          invalid-user {:id -1 :name "" :email "invalid-email" :age 200}]
      
      (is (true? (validator/validate-data schema valid-user)))
      (is (false? (validator/validate-data schema invalid-user))))))
