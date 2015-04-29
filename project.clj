(defproject authorize-net-clj "0.1.0"
  :description "Clojure library for Authorize.Net payment API"
  :url "https://github.com/sventechie/authorize-net-clj"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/sventechie/authorize-net-clj"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [clj-http "1.0.1"]
                 [org.clojure/data.csv "0.1.2"]
                 [cheshire "5.4.0"] ; JSON format
                 [prismatic/plumbing "0.3.7"] ; argument checking
                 ;[org.clojure/core.incubator "0.1.3"] ; dissoc-in function
                 [enlive "1.1.5"] ; XML templating
                 [org.clojure/data.xml "0.0.8"]])
