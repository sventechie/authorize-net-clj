(ns authorize-net-clj.core-test
  (:require [clojure.test :refer :all]
            [authorize-net-clj.core :refer :all]))

(def billing-address
  { :cust_id "1234"
    :first_name "John"
    :last_name "Doe"
    :address "1234 Street"
    :city "Bellevue"
    :state "WA"
    :zip "98004"
    :country "US"
    :phone "123-456-7890"
    :email "my.email@email.com" })

(def shipping-address
  { :first_name "John"
    :last_name "Doe"
    :address "1234 Street"
    :city "Bellevue"
    :state "WA"
    :zip "98004"
    :country "US" })

(def example-items
  [{:id "Item1"
    :name "green bauble"
    :description "description1"
    :quantity 1
    :price "4.00"}
   {:id "Item2"
    :name "blue bauble"
    :description "description2"
    :quantity 1
    :price "15.00"
    :taxable true}])

(def example-transaction-map
  {:invoice_num "1234"
   :customer_ip "8.8.8.8"
   :description "Purchase from Online Store"
   :amount 19.99
   :tax 3.21
   :freight {:name "Shipping" :description "Ground Overnight" :price 5.95}
   :line_items example-items
   :card_num "5424000000000015"
   :card_code "999"
   :exp_date "1220"
   :billing_address billing-address
   :shipping_address shipping-address})

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))
