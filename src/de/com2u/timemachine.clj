(ns de.com2u.timemachine
  (:require [com.biffweb :as biff]
            [de.com2u.timemachine.email :as email]
            [de.com2u.timemachine.app :as app]
            [de.com2u.timemachine.home :as home]
            [de.com2u.timemachine.middleware :as mid]
            [de.com2u.timemachine.ui :as ui]
            [de.com2u.timemachine.schema :as schema]
            [de.com2u.timemachine.game :as game] ; Added game namespace
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as tn-repl]
            [malli.core :as malc]
            [malli.registry :as malr]
            [nrepl.cmdline :as nrepl-cmd]
            [clojure.java.io :as io]) ; Added for file operations
  (:gen-class))

(def modules
  [app/module
   (biff/authentication-module {})
   home/module
   schema/module])

(def routes [["" {:middleware [mid/wrap-site-defaults]}
              (keep :routes modules)]
             ["" {:middleware [mid/wrap-api-defaults]}
              (keep :api-routes modules)]])

(def handler (-> (biff/reitit-handler {:routes routes})
                 mid/wrap-base-defaults))

(def static-pages (apply biff/safe-merge (map :static modules)))

(defn generate-assets! [ctx]
  (biff/export-rum static-pages "target/resources/public")
  (biff/delete-old-files {:dir "target/resources/public"
                          :exts [".html"]})
  ;; Removed custom CSS copying. Relies on `clj -M:dev css` outputting to
  ;; target/resources/public/css/styles.css and Biff middleware serving it.
  (log/info "generate-assets! completed (RUM export and old file deletion)."))

(defn on-save [ctx]
  (biff/add-libs)
  (biff/eval-files! ctx)
  (generate-assets! ctx)
  (test/run-all-tests #"de.com2u.timemachine.*-test"))

(def malli-opts
  {:registry (malr/composite-registry
              malc/default-registry
              (apply biff/safe-merge (keep :schema modules)))})

(def initial-system
  {:biff/modules #'modules
   :biff/send-email #'email/send-email
   :biff/handler #'handler
   :biff/malli-opts #'malli-opts
   :biff.beholder/on-save #'on-save
   :biff.middleware/on-error #'ui/on-error

   :de.com2u.timemachine/chat-clients (atom #{})
   :de.com2u.timemachine/game-clients (atom #{})}) ; Added game-clients atom

(defonce system (atom {}))

(def components
  [biff/use-aero-config
   biff/use-queues
   biff/use-htmx-refresh
   biff/use-jetty
   biff/use-chime
   biff/use-beholder])

(defn start []
  (let [new-system (reduce (fn [system component]
                             (log/info "starting:" (str component))
                             (component system))
                            initial-system
                            components)]
     (reset! system new-system)
     (generate-assets! new-system)
     (game/start-simulation! (partial app/broadcast-game-state new-system)) ; Start game simulation
     (log/info "System started.")
    (log/info "Go to" (:biff/base-url new-system))
    new-system))

(defn -main []
  (let [{:keys [biff.nrepl/args]} (start)]
    (apply nrepl-cmd/-main args)))

(defn refresh []
  (doseq [f (:biff/stop @system)]
    (log/info "stopping:" (str f))
    (f))
  (tn-repl/refresh :after `start)
  :done)
