(ns client.mobile.views
  "Mobile-specific UI. Shows transactions for the current period using React Native components."
  (:require [re-frame.core :refer [subscribe]]
            [reagent.core :as r]
            [clojure.string :as s]
            [client.services.date-service :as date])
  (:require ["react-native" :refer [View Text ScrollView StyleSheet FlatList]]))

(def styles
  (StyleSheet.create
   #js {:container #js {:flex 1
                        :paddingTop 40
                        :paddingHorizontal 16
                        :backgroundColor "#f5f5f5"}
        :header #js {:fontSize 24
                    :fontWeight "bold"
                    :marginBottom 16
                    :color "#333"}
        :periodLabel #js {:fontSize 14
                         :color "#666"
                         :marginBottom 12}
        :row #js {:flexDirection "row"
                  :justifyContent "space-between"
                  :alignItems "center"
                  :paddingVertical 12
                  :paddingHorizontal 12
                  :backgroundColor "#fff"
                  :marginBottom 6
                  :borderRadius 8
                  :shadowColor "#000"
                  :shadowOffset #js {:width 0 :height 1}
                  :shadowOpacity 0.05
                  :shadowRadius 2
                  :elevation 2}
        :rowDate #js {:fontSize 12
                      :color "#666"
                      :marginRight 8}
        :rowDesc #js {:flex 1
                     :fontSize 14
                     :color "#333"}
        :rowAmount #js {:fontSize 14
                        :fontWeight "600"
                        :color "#333"}
        :rowAmountNegative #js {:fontSize 14
                                  :fontWeight "600"
                                  :color "#c00"}
        :empty #js {:padding 24
                    :alignItems "center"
                    :color "#999"}}))

(defn format-date [d]
  (cond
    (nil? d) ""
    (string? d) (-> d (s/split #"T") first)
    (instance? js/Date d) (-> d .toISOString (s/split #"T") first)
    (number? d) (format-date (js/Date. d))
    :else (str d)))

(defn transaction-row [_ transaction]
  (let [amount (or (:amount transaction) 0)
        negative? (neg? amount)]
    [:> View {:style (.-row styles)}
     [:> View {:style #js {:flex 1}}
      [:> Text {:style (.-rowDate styles)} (format-date (:date transaction))]
      [:> Text {:style (.-rowDesc styles) :numberOfLines 2}
       (or (:description transaction) "-")]]
     [:> Text {:style (if negative? (.-rowAmountNegative styles) (.-rowAmount styles))}
      (str (when (pos? amount) "+") amount)]]))

(defn transactions-list []
  (let [period (subscribe [:period])
        transactions (subscribe [:period-transactions])
        loading (subscribe [:loading])]
    (fn []
      (cond
        (= @loading "true")
        [:> View {:style (.-container styles)}
         [:> Text {:style (.-header styles)} "Loading..."]]

        (empty? @transactions)
        [:> View {:style (.-container styles)}
         [:> Text {:style (.-header styles)} "Transactions"]
         [:> Text {:style (.-periodLabel styles)}
          (str "Current period: " (format-date (-> @period :start)) " – " (format-date (-> @period :end)))]
         [:> View {:style (.-empty styles)}
          [:> Text "No transactions for this period."]]]

        :else
        [:> View {:style (.-container styles)}
         [:> Text {:style (.-header styles)} "Transactions"]
         [:> Text {:style (.-periodLabel styles)}
          (str "Current period: " (format-date (-> @period :start)) " – " (format-date (-> @period :end)))]
         [:> FlatList
          {:data (clj->js @transactions)
           :renderItem (fn [^js info]
                         (let [item (.-item info)
                               t (js->clj item :keywordize-keys true)]
                           (r/as-element [transaction-row nil t])))
           :style #js {:flex 1}
           :keyExtractor (fn [^js item index] (str index "-" (.-date item) "-" (.-amount item)))}]]))))

(defn odin-mobile-app []
  [:> View {:style (.-container styles)}
   [transactions-list]])
