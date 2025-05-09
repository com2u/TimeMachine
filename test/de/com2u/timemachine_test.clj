(ns de.com2u.timemachine-test
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [de.com2u.timemachine :as main]
            [de.com2u.timemachine.app :as app]
            [malli.generator :as mg]
            [rum.core :as rum]))

(deftest example-test
  (is (= 4 (+ 2 2))))

(defn get-context [node]
  {:biff/db         (xt/db node)
   :biff/malli-opts #'main/malli-opts})


