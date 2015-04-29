# authorize-net-clj

A Clojure library designed to process payments through Authorize.Net.
We use the HTTP API endpoints, not a wrapper for the Java code.

Currently supporting the old CSV based AIM API version 3.1,
and partially supporting the newer XML API and
not yet the (currently) beta "JSON" endpoint.

## WARNING
This is very rough. You're welcome to play around and contribute pull requests.
Not in production yet.

## Usage
```
[sventechie/authorize-net-clj "0.1.0"]
```
Create a config file in `resources/authorize-net-config.edn`
and with the following contents:

```
 { :api-login "xxxxxxxx"       ;; <= 20 chars
   :api-key "yyyyyyyyyyyyyyyy" ;; == 16 chars
   :email-receipt true         ;; send customer receipt
   :market-type 0              ;; 0 = ecommerce, 1 = moto, 2 = retail/card present
   :test-mode true             ;; test flage, allowed in both production & test endpoints
   :processing-endpoint :test
   :default-description "Online Purchase"
 }
```

Note that reseller-provided credentials will only work with the production endpoint.
Just set test mode to `true` for testing.

Parameter names follow the official documents except the `x_` prefix.
Use CSV, XML or JSON like so:

```
(:require [authorize-net-clj.csv :as auth-csv])

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
    :email "you@email.com" })

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
   :description "Purchase from Crossroads Music Store"
   :amount 19.99
   :tax 3.21
   :freight {:name "Shipping" :description "Ground Overnight" :price 5.95}
   :line_items example-items
   :card_num "4111111111111111"
   :card_code "123"
   :exp_date "0415"
   :billing_address billing-address
   :shipping_address shipping-address})
```

## Error Codes

The following URL has info about error code meaning:

http://developer.authorize.net/tools/responsereasoncode/

## License

Copyright © 2015 Sven Pedersen

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
