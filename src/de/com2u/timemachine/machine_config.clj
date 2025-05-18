(ns de.com2u.timemachine.machine-config
  (:gen-class))

;; This file defines the machine configuration that will be provided to the frontend
;; The frontend will dynamically render components based on this configuration

(defn get-component-types []
  "Returns the available component types and their properties"
  {:generator {:display-name "Generator"
               :description "Produces output values at a configurable interval"
               :configurable-properties [:enabled? :interval]
               :display-properties [:value :heat :is-on?]
               :ui-elements {:value {:type :digital-display
                                     :label "Output"
                                     :color "#6ee7b7"}
                             :heat {:type :gauge
                                    :label "Heat"
                                    :min 20.0
                                    :max 150.0
                                    :zones [{:min 20 :max 70 :color "#60a5fa"}   ;; Cool (Blue)
                                            {:min 70 :max 120 :color "#facc15"}  ;; Warm (Yellow)
                                            {:min 120 :max 150 :color "#ef4444"}] ;; Hot (Red)
                                    }
                             :interval {:type :slider
                                        :label "Speed"
                                        :min 0
                                        :max 10000
                                        :step 100
                                        :gauge {:min 0
                                                :max 10000
                                                :zones [{:min 0 :max 3300 :color "#22c55e"}     ;; Fast = Green
                                                        {:min 3300 :max 6600 :color "#facc15"}  ;; Medium = Yellow
                                                        {:min 6600 :max 10000 :color "#ef4444"}] ;; Slow = Red
                                                :inverted true}}
                             :enabled? {:type :led-toggle
                                        :label "Status"
                                        :on-color "#22c55e"
                                        :off-color "#ef4444"}}}
   
   :products {:display-name "Products"
              :description "Combines inputs from generators to produce products"
              :configurable-properties []
              :display-properties [:product :current-input-a :current-input-b :current-input-c]
              :ui-elements {:product {:type :digital-display
                                      :label "Products"
                                      :color "#fb923c"
                                      :large true}
                            :current-input-a {:type :text-display
                                              :label "Input 1"}
                            :current-input-b {:type :text-display
                                              :label "Input 2"}
                            :current-input-c {:type :text-display
                                              :label "Input 3"}}}
   
   :consumer {:display-name "Consumer"
              :description "Consumes products at a configurable rate"
              :configurable-properties [:enabled? :interval :amount]
              :display-properties []
              :ui-elements {:enabled? {:type :led-toggle
                                       :label "Status"
                                       :on-color "#22c55e"
                                       :off-color "#ef4444"}
                            :amount {:type :slider
                                     :label "Amount"
                                     :min 1
                                     :max 10
                                     :step 1}
                            :interval {:type :slider
                                       :label "Interval"
                                       :min 0
                                       :max 1000
                                       :step 100}}}
   
   :energy-monitor {:display-name "Energy Monitor"
                    :description "Monitors energy levels from inputs"
                    :configurable-properties []
                    :display-properties [:output-value :current-input-a :current-input-b]
                    :ui-elements {:output-value {:type :gauge-with-display
                                                 :label "Energy Level"
                                                 :min 0
                                                 :max 100
                                                 :zones [{:min 0 :max 33 :color "#22c55e"}    
                                                         {:min 33 :max 66 :color "#facc15"}  
                                                         {:min 66 :max 100 :color "#ef4444"}]}
                                  :current-input-a {:type :text-display
                                                    :label "Input 1"}
                                  :current-input-b {:type :text-display
                                                    :label "Input 2"}}}})

(defn get-machine-configuration []
  "Returns the current machine configuration with all components"
  {:components
   {:generator-1 {:id :generator-1
                  :type :generator
                  :position {:row 0 :col 0}
                  :connections {:outputs [{:topic :generator-1-output
                                           :target-components [:products :energy-monitor]}]}}
    
    :generator-2 {:id :generator-2
                  :type :generator
                  :position {:row 0 :col 1}
                  :connections {:outputs [{:topic :generator-2-output
                                           :target-components [:products :energy-monitor]}]}}
    
    :generator-3 {:id :generator-3
                  :type :generator
                  :position {:row 1 :col 3}
                  :connections {:outputs [{:topic :generator-3-output
                                           :target-components [:products]}]}}
    
    :products {:id :products
               :type :products
               :position {:row 0 :col 2}
               :connections {:inputs [{:source :generator-1
                                       :topic :generator-1-output
                                       :target-property :current-input-a}
                                      {:source :generator-2
                                       :topic :generator-2-output
                                       :target-property :current-input-b}
                                      {:source :generator-3
                                       :topic :generator-3-output
                                       :target-property :current-input-c}]}}
    
    :consumer-1 {:id :consumer-1
                 :type :consumer
                 :position {:row 1 :col 0}
                 :connections {:inputs [{:source :products
                                         :property :product}]}}
    
    :energy-monitor {:id :energy-monitor
                     :type :energy-monitor
                     :position {:row 1 :col 1}
                     :connections {:inputs [{:source :generator-1
                                             :topic :generator-1-output
                                             :target-property :current-input-a}
                                            {:source :generator-2
                                             :topic :generator-2-output
                                             :target-property :current-input-b}]}}}})

(defn get-initial-component-states []
  "Returns the initial state for all components in the machine"
  (let [current-time (System/currentTimeMillis)]
    {:generator-1 {:id :generator-1
                   :type :generator
                   :enabled? true
                   :interval 1000
                   :last-tick current-time
                   :value 0
                   :heat 20.0
                   :max-heat 150.0
                   :is-on? true}
     
     :generator-2 {:id :generator-2
                   :type :generator
                   :enabled? true
                   :interval 2000
                   :last-tick current-time
                   :value 0
                   :heat 20.0
                   :max-heat 150.0
                   :is-on? true}
     
     :generator-3 {:id :generator-3
                   :type :generator
                   :enabled? true
                   :interval 1500
                   :last-tick current-time
                   :value 0
                   :heat 20.0
                   :max-heat 150.0
                   :is-on? true}
     
     :products {:id :products
                :type :products
                :input-a-topic :generator-1-output
                :input-b-topic :generator-2-output
                :input-c-topic :generator-3-output
                :current-input-a nil
                :current-input-b nil
                :current-input-c nil
                :product 0}
     
     :consumer-1 {:id :consumer-1
                  :type :consumer
                  :enabled? true
                  :interval 1000
                  :amount 1
                  :last-tick current-time}
     
     :energy-monitor {:id :energy-monitor
                      :type :energy-monitor
                      :input-a-topic :generator-1-output
                      :input-b-topic :generator-2-output
                      :current-input-a nil
                      :current-input-b nil
                      :output-value nil}}))
