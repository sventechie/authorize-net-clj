(ns authorize-net-clj.csv-test
  (:require [clojure.test :refer :all]
            [authorize-net-clj.core :refer :all]
            [authorize-net-clj.csv :as payment]
            [authorize-net-clj.core-test :as common]))


(defn basic-transaction
  "Do a simple credit card transaction"
  []
  (-> common/example-transaction-map
      payment/card-transaction
      payment/raw-transaction
      payment/parse-response))

(deftest basic-test
  (testing "Valid response code from transaction"
    (is (:response_code (basic-transaction)))))
