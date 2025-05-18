(ns de.com2u.timemachine.app
  (:require [com.biffweb :as biff :refer [q]]
            [de.com2u.timemachine.middleware :as mid]
            [de.com2u.timemachine.ui :as ui]
            [de.com2u.timemachine.settings :as settings]
            [de.com2u.timemachine.game :as game]
            [de.com2u.timemachine.machine-config :as machine-config]
            [rum.core :as rum]
            [ring.adapter.jetty9 :as jetty]
            [cheshire.core :as cheshire]
            [xtdb.api :as xt]))



(defn bar-form [{:keys [value]}]
  (biff/form
   {:hx-post "/app/set-bar"
    :hx-swap "outerHTML"}
   [:label.block {:for "bar"} "Bar: "
    [:span.font-mono (pr-str value)]]
   [:.h-1]
   [:.flex
    [:input.w-full#bar {:type "text" :name "bar" :value value}]
    [:.w-3]
    [:button.btn {:type "submit"} "Update"]]
   [:.h-1]
   [:.text-sm.text-gray-600
    "This demonstrates updating a value with HTMX."]))



(defn message [{:msg/keys [text sent-at]}]
  [:.mt-3 {:_ "init send newMessage to #message-header"}
   [:.text-gray-600 (biff/format-date sent-at "dd MMM yyyy HH:mm:ss")]
   [:div text]])

(defn notify-clients [{:keys [de.com2u.timemachine/chat-clients]} tx]
  (doseq [:let [html (rum/render-static-markup
                      [:div#messages {:hx-swap-oob "afterbegin"}
                       ])]
          ws @chat-clients]
    (jetty/send! ws html)))

(defn send-message [{:keys [session] :as ctx} {:keys [text]}]
  (let [{:keys [text]} (cheshire/parse-string text true)]
    (biff/submit-tx ctx
      [{:db/doc-type :msg
        :msg/user (:uid session)
        :msg/text text
        :msg/sent-at :db/now}])))

(defn chat [{:keys [biff/db]}]
  (let [messages (q db
                    '{:find (pull msg [*])
                      :in [t0]
                      :where [[msg :msg/sent-at t]
                              [(<= t0 t)]]}
                    (biff/add-seconds (java.util.Date.) (* -60 10)))]
    [:div {:hx-ext "ws" :ws-connect "/app/chat"}
     [:form.mb-0 {:ws-send true
                  :_ "on submit set value of #message to ''"}
      [:label.block {:for "message"} "Write a message"]
      [:.h-1]
      [:textarea.w-full#message {:name "text"}]
      [:.h-1]
      [:.text-sm.text-gray-600
       "Sign in with an incognito window to have a conversation with yourself."]
      [:.h-2]
      [:div [:button.btn {:type "submit"} "Send message"]]]
     [:.h-6]
     [:div#message-header
      {:_ "on newMessage put 'Messages sent in the past 10 minutes:' into me"}
      (if (empty? messages)
        "No messages yet."
        "Messages sent in the past 10 minutes:")]
       [:div#messages
      (map message (sort-by :msg/sent-at #(compare %2 %1) messages))]]))

;; --- Game WebSocket Logic ---

(defn broadcast-game-state [{:keys [de.com2u.timemachine/game-clients]} game-state]
  (let [json-state (cheshire/generate-string game-state)]
    (doseq [ws @game-clients]
      (jetty/send! ws json-state))))

(defn game-ws-handler [{:keys [de.com2u.timemachine/game-clients] :as ctx}]
  {:status 101
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   :ws {:on-connect (fn [ws]
                      (println "Game client connected:" ws)
                      (swap! game-clients conj ws)
                      ;; Send initial configuration and state to the newly connected client
                      (let [machine-config (game/get-machine-config)
                            initial-state (game/get-controls-state)
                            initial-data {:config machine-config
                                          :state initial-state}]
                        (jetty/send! ws (cheshire/generate-string initial-data))))
        :on-text (fn [ws text-message]
                   (try
                     (let [message (cheshire/parse-string text-message true)
                           command (:command message)
                           control-id (keyword (:controlId message)) ; Ensure controlId is a keyword
                           value (:value message)]
                       (println "Received command:" command "for control:" control-id "with value:" value)
                       (cond
                         (= command "toggle-enabled")
                         (when control-id (game/toggle-control-enabled! control-id))

                         (= command "set-interval")
                         (when (and control-id value) (game/set-control-interval! control-id value))

                         (= command "reset-generators")
                         (game/reset-generators!)
                         
                         :else
                         (println "Unknown command received:" command))
                       
                       ;; After processing, broadcast the new state immediately
                       (broadcast-game-state ctx {:state (game/get-controls-state)}))
                     (catch Exception e
                       (println "Error processing command from client:" (.getMessage e) "Raw message:" text-message)
                       (.printStackTrace e))))
        :on-close (fn [ws status-code reason]
                    (println "Game client disconnected:" ws "Status:" status-code "Reason:" reason)
                    (swap! game-clients disj ws))}})

;; --- End Game WebSocket Logic ---

(defn app [{:keys [session biff/db] :as ctx}]
  (let [{:user/keys [email foo bar]} (xt/entity db (:uid session))]
    (ui/page
     {}
     [:div "Signed in as " email ". "
      (biff/form
       {:action "/auth/signout"
        :class "inline"}
       [:button.text-blue-500.hover:text-blue-800 {:type "submit"}
        "Sign out"])
      "."]
     [:.h-6]
     (biff/form
      {:action "/app/set-foo"}
      [:label.block {:for "foo"} "Foo: "
       [:span.font-mono (pr-str foo)]]
      [:.h-1]
      [:.flex
       [:input.w-full#foo {:type "text" :name "foo" :value foo}]
       [:.w-3]
       [:button.btn {:type "submit"} "Update"]]
      [:.h-1]
      [:.text-sm.text-gray-600
       "This demonstrates updating a value with a plain old form."])
     [:.h-6]
     (bar-form {:value bar})
     [:.h-6]
     (chat ctx))))

(defn ws-handler [{:keys [de.com2u.timemachine/chat-clients] :as ctx}]
  {:status 101
   :headers {"upgrade" "websocket"
             "connection" "upgrade"}
   :ws {:on-connect (fn [ws]
                      (swap! chat-clients conj ws))
        :on-text (fn [ws text-message]
                   (send-message ctx {:ws ws :text text-message}))
        :on-close (fn [ws status-code reason]
                    (swap! chat-clients disj ws))}})

(def about-page
  (ui/page
   {:base/title (str "About " settings/app-name)}
   [:p "This app was made with "
    [:a.link {:href "https://biffweb.com"} "Biff"] "."]))

(defn echo [{:keys [params]}]
  {:status 200
   :headers {"content-type" "application/json"}
   :body params})

(def module
  {:static {"/about/" about-page}
   :routes ["/app" {:middleware [mid/wrap-signed-in]}
            ["" {:get app}]
            ;; Removed /set-foo and /set-bar routes as they are not defined
            ;; ["/set-foo" {:post set-foo}]
            ;; ["/set-bar" {:post set-bar}]
            ["/chat" {:get ws-handler}]]
   :api-routes [["/api/echo" {:post echo}]
                ["/ws/game" {:get game-ws-handler}]] ; Added game WebSocket route
   :on-tx notify-clients})

;; IMPORTANT:
;; 1. Initialize game-clients atom in your Biff system map:
;;    Add something like :de.com2u.timemachine/game-clients (atom #{})
;;    to the components map in your main or dev/repl.clj.
;;
;; 2. Start the game simulation after system start:
;;    In your main or dev/repl.clj, after (biff/start-system components) or similar,
;;    you need to call (game/start-simulation! broadcast-game-state-fn)
;;    where broadcast-game-state-fn is a function that can access the
;;    game-clients atom from the system map.
;;    For example, in your main:
;;    (let [system (biff/start-system ...)]
;;      (game/start-simulation! (fn [state] (broadcast-game-state system state)))
;;      ;; ... keep system running)
;;    Ensure `broadcast-game-state` is adapted or called in a way that it gets the system map.
;;    A direct way if `broadcast-game-state` is defined as above:
;;    (game/start-simulation! (partial broadcast-game-state system))
;;    Or, more cleanly, pass the system map to broadcast-game-state if it's not already available.
;;    The `broadcast-game-state` function defined above expects the system map (or at least game-clients) as its first argument.
;;
;;    A more Biff-idiomatic way would be to make game simulation a component.
;;    For now, the above manual start is a simpler first step.
