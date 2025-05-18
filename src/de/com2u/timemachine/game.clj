(ns de.com2u.timemachine.game
  (:require [de.com2u.timemachine.machine-config :as machine-config])
  (:gen-class))

(defonce game-state
  (atom {;; :controls will be initialized in start-simulation!
         ;; :topics will store current values of outputs, e.g., {:counter-a-output 0}
         }))

(defn get-machine-config []
  "Returns the machine configuration for the frontend"
  {:component-types (machine-config/get-component-types)
   :machine-configuration (machine-config/get-machine-configuration)})

(defn- process-generator [control current-time]
  (let [interval-ms (:interval control)
        interval-secs (if (and interval-ms (pos? interval-ms)) (/ interval-ms 1000.0) 1.0) ; Avoid Div/0, default to 1s
        heat-change (/ (/ 1.0 interval-secs) 100.0)
        current-heat (:heat control 20.0)
        max-heat (:max-heat control 150.0)
        min-heat 20.0
        is-on? (:is-on? control true)] ; is-on? will determine if generator is running or shut off due to heat

    (if is-on?
      ;; Generator is ON and producing
      (let [new-heat (min max-heat (+ current-heat heat-change))]
        (if (>= new-heat max-heat)
          ;; Heat limit reached, turn OFF
          {:updated-control (assoc control :heat max-heat :is-on? false :last-tick current-time)
           ;; No value publication as it's shutting off
           }
          ;; Still ON, heat increasing. Check if it's time to tick for value.
          (if (and (:enabled? control true) ; User-controlled enabled flag
                   (>= (- current-time (:last-tick control 0)) interval-ms))
            (let [new-value (inc (:value control 0))]
              {:updated-control (assoc control :value new-value :last-tick current-time :heat new-heat)
               :publication {:topic (keyword (str (name (:id control)) "-output")) :value new-value}})
            ;; Interval for value tick not met, just update heat
            {:updated-control (assoc control :heat new-heat)})))
      ;; Generator is OFF (due to heat)
      (let [new-heat (max min-heat (- current-heat heat-change))]
        ;; When off, it just cools down. No value publication.
        ;; Update last-tick to ensure heat calculation continues at intervals.
        (if (>= (- current-time (:last-tick control 0)) interval-ms)
          {:updated-control (assoc control :heat new-heat :last-tick current-time)}
          ;; Interval for heat calculation tick not met, just update heat (should not happen if last-tick is updated)
          ;; However, to be safe, ensure heat is always updated.
          {:updated-control (assoc control :heat new-heat)})))))

(defn- process-products [control topics-state] ; Renamed from process-multiplicator
  (let [;; Get current tick's generator outputs from topics, defaulting to 0.0 if a topic is missing
        gen-output-a (double (get topics-state (:input-a-topic control) 0.0))
        gen-output-b (double (get topics-state (:input-b-topic control) 0.0))
        gen-output-c (double (get topics-state (:input-c-topic control) 0.0))
        
        calculated-product (+ gen-output-a gen-output-b gen-output-c) ; Direct sum of current generator outputs
        
        final-stock (min 10000 (max 0 calculated-product))] ; Clamp the sum
    {:updated-control (assoc control
                               :current-input-a gen-output-a ; Display current generator outputs feeding in
                               :current-input-b gen-output-b
                               :current-input-c gen-output-c
                               :product final-stock)}))

(defn- process-energy-monitor [control topics-state] ; Renamed from process-divider
  (let [input-a (get topics-state (:input-a-topic control))
        input-b (get topics-state (:input-b-topic control))]
    {:updated-control (assoc control
                               :current-input-a input-a
                               :current-input-b input-b
                               :output-value (if (and (some? input-a) (some? input-b)) ; Changed :quotient to :output-value for generic gauge
                                           (if (not (zero? (double input-b)))
                                             (/ (double input-a) (double input-b))
                                             0.0) ; Return 0 or some other value for gauge on Div/0
                                           nil))}))

(defn- process-consumer [consumer-control current-product-value current-time]
  (if (and (:enabled? consumer-control true)
           (>= (- current-time (:last-tick consumer-control 0)) (:interval consumer-control)))
    (let [amount-to-try-consume (:amount consumer-control 1)]
      (if (< current-product-value amount-to-try-consume)
        ;; Not enough products, disable consumer, consume 0
        {:updated-consumer (assoc consumer-control :enabled? false :last-tick current-time) 
         :amount-consumed 0}
        ;; Enough products, consume
        {:updated-consumer (assoc consumer-control :last-tick current-time)
         :amount-consumed amount-to-try-consume}))
    ;; Not enabled or interval not met, consume 0
    {:updated-consumer consumer-control 
     :amount-consumed 0}))

(defn simulation-tick []
  (let [current-time (System/currentTimeMillis)]
    (swap! game-state
           (fn [{:keys [controls topics] :as current-gs}]
             (if (nil? controls)
               current-gs
               (let [;; Step 1: Process generators
                     gen-results (mapv (fn [[id ctl]]
                                         (when (= (:type ctl) :generator)
                                           (assoc (process-generator ctl current-time) :id id)))
                                       controls)
                     valid-gen-results (remove nil? gen-results)
                     controls-after-gens (reduce (fn [acc res] (assoc acc (:id res) (:updated-control res)))
                                                 controls valid-gen-results)
                     topics-after-gens (reduce (fn [acc res]
                                                 (if-let [pub (:publication res)]
                                                   (assoc acc (:topic pub) (:value pub))
                                                   acc))
                                               topics valid-gen-results)

                     ;; Step 2: Process products component (it reads from topics updated by generators)
                     products-control-key :products ; Assuming there's one products component
                     products-ctl (get controls-after-gens products-control-key)
                     processed-products-result (process-products products-ctl topics-after-gens)
                     interim-controls-after-products (assoc controls-after-gens products-control-key (:updated-control processed-products-result))

                     ;; Check if products are maxed out and disable generators if so
                     products-at-max? (>= (get-in interim-controls-after-products [products-control-key :product] 0) 10000)
                     
                     controls-after-product-check (if products-at-max?
                                                    (reduce-kv (fn [m k v]
                                                                 (if (= (:type v) :generator)
                                                                   (assoc m k (assoc v :enabled? false))
                                                                   m))
                                                               interim-controls-after-products
                                                               interim-controls-after-products)
                                                    interim-controls-after-products)

                     ;; Step 3: Process consumers
                     current-product-value (get-in controls-after-product-check [products-control-key :product] 0)

                     consumer-updates (doall ; Eagerly process consumers
                                        (for [[id ctl] controls-after-product-check :when (= (:type ctl) :consumer)]
                                          (assoc (process-consumer ctl current-product-value current-time) :id id)))
      
                     total-consumed (reduce + 0 (map :amount-consumed consumer-updates))
                     new-product-value (max 0 (- current-product-value total-consumed))

                     controls-with-updated-consumers-and-products (as-> controls-after-product-check acc
                                                                    ;; Apply individual consumer state updates
                                                                    (reduce (fn [m consumer-update-result]
                                                                              (assoc m (:id consumer-update-result) (:updated-consumer consumer-update-result)))
                                                                            acc
                                                                            consumer-updates)
                                                                    ;; Apply the final product update
                                                                    (assoc-in acc [products-control-key :product] new-product-value))
                     
                     ;; Step 4: Process energy monitors (they read from topics)
                     final-controls (reduce (fn [acc [id ctl]]
                                              (if (= (:type ctl) :energy-monitor)
                                                (assoc acc id (:updated-control (process-energy-monitor ctl topics-after-gens)))
                                                acc))
                                            controls-with-updated-consumers-and-products ; Use the state after consumption
                                            controls-with-updated-consumers-and-products)] ; Corrected: removed extra ')' before ']'
                 {:controls final-controls :topics topics-after-gens}))))))

(defn get-controls-state []
  (:controls @game-state))

(defonce simulation-active (atom false))
(defonce simulation-runner (atom nil))

(defn start-simulation! [broadcast-fn]
  (when (compare-and-set! simulation-active false true)
    (println "Starting TimeMachine simulation...")
    (let [initial-controls (machine-config/get-initial-component-states)]
      (reset! game-state {:controls initial-controls :topics {}}))

    (reset! simulation-runner
            (future
              (try ;; Outer try for InterruptedException and the main finally clause
                (loop [last-broadcast-state nil]
                  (if @simulation-active
                    (let [[next-broadcast-state sleep-duration] ; Calculate next state and sleep before recur
                          (try
                            (simulation-tick)
                            (let [current-state (get-controls-state)]
                              (if (not= current-state last-broadcast-state)
                                (do
                                  (broadcast-fn current-state)
                                  [current-state 100]) ; Return new state and sleep duration
                                [last-broadcast-state 100])) ; Return old state and sleep duration (if no change)
                          (catch Exception e
                            (println "Error during simulation tick:" (str (.getClass e) " " (.getMessage e)))
                            (.printStackTrace e)
                            [last-broadcast-state 1000]))] ; Return old state and longer sleep on error
                      (Thread/sleep sleep-duration)
                      (recur next-broadcast-state)) ; Single recur point
                    (println "Simulation marked inactive, loop terminating."))) ; False branch of (if @simulation-active)
              (catch InterruptedException _
                (println "Simulation thread interrupted (e.g., by stop-simulation! or JVM shutdown)."))
              (finally
                ;; This finally block executes when the future's main body is exiting,
                ;; either normally (loop terminates) or due to an InterruptedException.
                (reset! simulation-active false) ; Ensure state reflects simulation is stopped
                (println "Simulation future has finished."))))
            ) ; Closes future
    (println "TimeMachine simulation background task initiated.")) ; Closes println, and outer when
) ; Closes defn

;; --- Control Command Functions ---

(defn toggle-control-enabled! [control-key]
  (swap! game-state update-in [:controls control-key :enabled?] not))

(defn set-control-interval! [control-key new-interval-ms]
  ;; new-interval-ms is speed, 0-10s. Frontend sends ms.
  (println "Attempting to set interval for" control-key "to" new-interval-ms (type new-interval-ms))
  (if (and (number? new-interval-ms) (>= new-interval-ms 0) (<= new-interval-ms 10000)) ; 0 to 10000 ms
    (do
      (swap! game-state assoc-in [:controls control-key :interval] new-interval-ms)
      (println "Successfully set interval for" control-key "to" new-interval-ms)
      ;; (println "Current interval in state for" control-key ":" (get-in @game-state [:controls control-key :interval])) ; Redundant with frontend
      )
    (println "Invalid interval received for" control-key ":" new-interval-ms)))

(defn set-consumer-amount! [control-key new-amount-raw]
  (println "Attempting to set amount for" control-key "to" new-amount-raw (type new-amount-raw))
  (let [amount-val (cond
                     (string? new-amount-raw) (try (Integer/parseInt new-amount-raw) (catch NumberFormatException _ nil))
                     (instance? Number new-amount-raw) (try (int new-amount-raw) (catch Exception _ nil)) ; Convert to int, catch potential overflow if it's a huge double/long
                     :else nil)]
    (if (and amount-val (integer? amount-val) (>= amount-val 1) (<= amount-val 10))
      (do
        (swap! game-state assoc-in [:controls control-key :amount] amount-val)
        (println "Successfully set amount for" control-key "to" amount-val))
      (println "Invalid amount (must be integer 1-10). Received for" control-key ":" new-amount-raw "(processed as:" amount-val ")"))))

(defn reset-generators! [] ; This also effectively resets products as generators start from 0
  (println "Resetting generators (and implicitly products/consumer state)...")
  (swap! game-state
         (fn [current-s]
           (let [current-time (System/currentTimeMillis)]
             (-> current-s
                 (assoc-in [:controls :generator-1 :value] 0)
                 (assoc-in [:controls :generator-1 :last-tick] current-time)
                 (assoc-in [:controls :generator-1 :heat] 20.0)
                 (assoc-in [:controls :generator-1 :is-on?] true)
                 (assoc-in [:controls :generator-2 :value] 0)
                 (assoc-in [:controls :generator-2 :last-tick] current-time)
                 (assoc-in [:controls :generator-2 :heat] 20.0)
                 (assoc-in [:controls :generator-2 :is-on?] true)
                 (assoc-in [:controls :generator-3 :value] 0)
                 (assoc-in [:controls :generator-3 :last-tick] current-time)
                 (assoc-in [:controls :generator-3 :heat] 20.0)
                 (assoc-in [:controls :generator-3 :is-on?] true)
                 ; Reset consumer state as well, or rely on product reset?
                 ; For now, let's reset its last_tick to align with simulation restart.
                 ; Amount and interval are user-set, enabled can be reset.
                 (assoc-in [:controls :consumer-1 :last-tick] current-time)
                 (assoc-in [:controls :consumer-1 :enabled?] true) ; Or keep its current enabled state? Resetting for consistency.
                 (assoc-in [:controls :products :product] 0))) ; Corrected closing parentheses
           )))

;; --- End Control Command Functions ---

(defn stop-simulation! []
  (when (compare-and-set! simulation-active true false)
    (println "Stopping TimeMachine simulation...")
    (when-let [runner @simulation-runner]
      (future-cancel runner)
      (reset! simulation-runner nil))
    (reset! game-state {}) ; Clear state
    (println "TimeMachine simulation stopped.")))

;; Ensure simulation stops on JVM shutdown
(.addShutdownHook (Runtime/getRuntime) (Thread. stop-simulation!))
