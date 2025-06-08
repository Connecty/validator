# OpenAPI 3.1 Validator with Malli

ClojureでMalliを使用してOpenAPI 3.1スキーマによる入力バリデーションを実行するライブラリです。

## 機能

* OpenAPI 3.1スキーマをMalliスキーマに変換
* データのバリデーション実行
* 人間が読みやすいエラーメッセージの生成
* 基本的なOpenAPI型のサポート（string, integer, number, boolean, array, object）
* 制約のサポート（minLength, maxLength, minimum, maximum, pattern等）
* anyOf, oneOf, allOfのサポート
* APIリクエスト・レスポンスバリデーション

## 依存関係

* Clojure 1.11.1+
* Malli 0.13.0+
* Cheshire（JSON処理用）

## 使用方法

### 基本的な使用例

```clojure
(require '[validator.openapi :as validator])

;; OpenAPIスキーマの定義
(def user-schema
  {:type "object"
   :required ["id" "name" "email"]
   :properties
   {:id {:type "integer" :minimum 1}
    :name {:type "string" :minLength 1 :maxLength 100}
    :email {:type "string" :format "email"}
    :age {:type "integer" :minimum 0 :maximum 150}
    :active {:type "boolean"}}})

;; バリデーション実行
(def valid-user {:id 1 :name "John Doe" :email "john@example.com" :age 30 :active true})
(def invalid-user {:id -1 :name "" :email "invalid-email" :age 200})

;; シンプルなバリデーション
(validator/validate-data user-schema valid-user)
;; => true

(validator/validate-data user-schema invalid-user)
;; => false

;; エラー詳細付きバリデーション
(validator/validate-with-errors user-schema invalid-user)
;; => {:valid? false
;;     :errors {:id ["should be at least 1"]
;;              :name ["should be at least 1"]
;;              :email ["should be a valid email"]}}
```

### 配列のバリデーション

```clojure
(def array-schema
  {:type "array"
   :items {:type "integer"}
   :minItems 1
   :maxItems 5})

(validator/validate-data array-schema [1 2 3])
;; => true

(validator/validate-data array-schema [])
;; => false (minItems制約違反)
```

### anyOf/oneOfのサポート

```clojure
(def union-schema
  {:anyOf [{:type "string"}
           {:type "integer"}]})

(validator/validate-data union-schema "hello")
;; => true

(validator/validate-data union-schema 42)
;; => true

(validator/validate-data union-schema [])
;; => false
```

### OpenAPI仕様ファイルからの読み込み

```clojure
;; OpenAPI仕様を読み込み
(def openapi-spec (validator/load-openapi-spec "api-spec.json"))

;; 特定のスキーマを取得
(def user-schema (validator/get-schema-from-spec openapi-spec "User"))

;; APIリクエストバリデーター作成
(def request-validator 
  (validator/create-request-validator openapi-spec "/users" "post"))

;; APIレスポンスバリデーター作成
(def response-validator 
  (validator/create-response-validator openapi-spec "/users" "get" 200))
```

## サポートしているOpenAPI機能

### 基本型

* `string` (format: date, date-time, email, uuid等)
* `integer` (format: int32, int64)
* `number` (format: float, double)
* `boolean`
* `array`
* `object`

### 制約

* **String**: minLength, maxLength, pattern
* **Number**: minimum, maximum, exclusiveMinimum, exclusiveMaximum
* **Array**: minItems, maxItems, uniqueItems
* **Object**: required, properties

### 複合型

* `anyOf`
* `oneOf`
* `allOf`
* `$ref` (参照)

## テスト実行

### 全テストを実行

```bash
# Kaochaテストランナーを使用（推奨）
clj -M:test -m kaocha.runner

# 標準のclojure.testを使用
clj -M:test -e "(require 'validator.openapi-test) (clojure.test/run-tests 'validator.openapi-test)"
```

### 特定のテストを実行

```bash
# 特定のテスト名前空間のみ実行
clj -M:test -m kaocha.runner --focus validator.openapi-test

# 特定のテスト関数のみ実行
clj -M:test -m kaocha.runner --focus validator.openapi-test/test-basic-string-validation
```

### REPLでテスト実行

```bash
clj -M:test
```

REPLで：

```clojure
;; テスト名前空間をロード
(require 'validator.openapi-test)

;; 全テストを実行
(clojure.test/run-tests 'validator.openapi-test)

;; 特定のテストを実行
(clojure.test/run-tests #'validator.openapi-test/test-basic-string-validation)
```

### テスト結果の詳細表示

```bash
# より詳細な出力
clj -M:test -m kaocha.runner --reporter documentation

# テストカバレッジ表示（cloverage使用時）
clj -M:test:coverage
```

## 開発

### プロジェクトセットアップ

```bash
# プロジェクトをクローン
git clone <repository-url>
cd connecty/validator

# 依存関係をインストール（deps.ednから自動解決）
clj -P -M:test:dev
```

### REPLでの開発

```bash
# 開発用REPLを起動
clj -M:dev

# またはnREPLサーバーを起動
clj -M:dev -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware]"
```

REPLで：

```clojure
;; 名前空間をロード
(require '[validator.openapi :as validator] :reload)

;; 使用例を実行
(validator/example-user-schema)

;; テストを実行
(require 'validator.openapi-test :reload)
(clojure.test/run-tests 'validator.openapi-test)
```

### デバッグとトラブルシューティング

```clojure
;; Malliスキーマの確認
(require '[malli.core :as m])
(def schema (validator/openapi-schema->malli {:type "string" :minLength 3}))
(m/explain schema "hi")  ; バリデーションエラーの詳細

;; 生成されたMalliスキーマの表示
(validator/openapi-schema->malli your-openapi-schema)
```

### プロジェクト構成

```text
connecty/validator/
├── deps.edn           # 依存関係とエイリアス設定
├── tests.edn          # Kaochaテスト設定
├── README.md          # このファイル
├── src/
│   └── connecty/
│       └── validator/
│           ├── openapi.clj     # メインの実装
│           └── examples.clj    # 使用例
└── test/
    └── connecty/
        └── validator/
            └── openapi_test.clj # テストスイート
```

### 継続的インテグレーション

GitHub Actionsでのテスト実行例：

```yaml
# .github/workflows/test.yml
name: Tests
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: DeLaGuardo/setup-clojure@master
        with:
          cli: latest
      - run: clj -M:test -m kaocha.runner
```

## ライセンス

MIT License
