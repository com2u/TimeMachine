(ns de.com2u.timemachine.home
  (:require [clj-http.client :as http]
            [com.biffweb :as biff]
            [de.com2u.timemachine.middleware :as mid]
            [de.com2u.timemachine.ui :as ui]
            [de.com2u.timemachine.settings :as settings]
            [rum.core :as rum]))



(defn home-page [ctx]
  (ui/page
   ctx
   [:h2.text-2xl.font-bold "Welcome to " settings/app-name "!"]))



(def module
  {:routes [["" {:middleware [mid/wrap-redirect-signed-in]}
             ["/"                  {:get home-page}]]]})
