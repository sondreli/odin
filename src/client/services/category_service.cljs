(ns client.services.category-service
  (:require [clojure.string :as s]
            ;; [odin.services.transaction-service :as transaction]
            ))

;; (defn mark-if-match [transaction builder-category]
;;   (let [lines (-> builder-category :marker :description)
;;         match (fn [t] (and (contains? transaction :description)
;;                            (s/includes? (-> transaction :description s/lower-case) t)))
;;         match-exists? (some match lines)
;;         color (if (-> builder-category :color some?) (:color builder-category) nil)]
;;     (if match-exists?
;;       (assoc transaction :color color)
;;       transaction)))

;; (defn match-fun [description subtext]
;;   (if (and (s/includes? subtext "regex:")
;;            (-> subtext (subs 0 6) (= "regex:")))
;;     (re-matches (re-pattern (str ".*" (-> subtext (subs 6) s/lower-case) ".*")) 
;;                 (s/lower-case description))
;;     (s/includes? (s/lower-case description) (s/lower-case subtext))))

;; (defn match? [builder-category transaction]
;;   (let [lines (-> builder-category :marker :description)
;;         desc (:description transaction)
;;         match-exists? (and (-> lines count (> 0))
;;                            (some? desc)
;;                            (some (partial match-fun desc) lines))]
;;     match-exists?))

;; (defn update-if-match [transaction builder-category update-match-fun update-nomatch-fun]
;;   (let [match-exists? (match? builder-category transaction)
;;         ]
;;     (if match-exists?
;;       (update-match-fun transaction)
;;       (update-nomatch-fun transaction))))

;; ;; color should be a list of two colors
;; (defn mark-transaction [color tran]
;;   (if (contains? tran :category)
;;     (assoc-in tran [:category :conflicting-color] color)
;;     (assoc tran :category {:color color})))

;; (defn mark-transactions [builder-category transactions]
;;     (let [color (if (-> builder-category :color some?) (:color builder-category) nil)
;;           update-match-fun (partial mark-transaction color)]
;;       (->> transactions
;;        (map #(update-if-match % builder-category update-match-fun identity))
;;        (into [])))
;;   )

;; (defn remove-category-if-same [category-name transaction]
;;   (if (= category-name (-> transaction :category :name))
;;     (dissoc transaction :category)
;;     transaction))

;; (defn update-match [builder-category transaction]
;;   (let [category (select-keys builder-category [:name :color])]
;;     (assoc transaction :category category)))

;; (defn have-category? [builder-category transaction]
;;   (= (:name builder-category)
;;      (-> transaction :category :name)))

;; (defn add-category [transactions builder-category]
;;   (let [category (select-keys builder-category [:name :color])
;;         update-match-fun (fn [tran] (assoc tran :category category))
;;         update-nomatch-fun (partial remove-category-if-same (:name builder-category))]
;;     (->> transactions
;;          (map #(update-if-match % builder-category update-match-fun update-nomatch-fun))
;;          (into []))))

;; (defn add-transaction [builder-category acc transaction]
;;   (cond (match? builder-category transaction)
;;         (let [updated-transaction (update-match builder-category transaction)]
;;           (-> acc
;;               (update-in [:updated-seq] conj updated-transaction)
;;               (update-in [:only-updates] conj (select-keys updated-transaction [:db-id :category]))))
;;         (have-category? builder-category transaction)
;;         (-> acc
;;             (update-in [:updated-seq] conj (dissoc transaction :category))
;;             (update-in [:only-updates] conj {:db-id (:db-id transaction)
;;                                              :category nil
;;                                              :old-category (:category transaction)}))
;;         :else
;;         (update-in acc [:updated-seq] conj transaction)))

;; (defn add-category2 [transactions builder-category]
;;   (let [fun (partial add-transaction builder-category)]
;;     (reduce fun {:updated-seq [] :only-updates []} transactions)))

;; (defn update-marker [text]
;;   (let [lines (s/split text #"\n")
;;         only-filled-lines (filter #(> (count %) 0) lines)]
;;     {:description only-filled-lines :value text}))

;; (defn ready-to-store? [builder-category]
;;   (and
;;    (-> builder-category :name some?)
;;    (-> builder-category :name count (> 0))
;;    (-> builder-category :color some?)
;;    (-> builder-category :marker some?)
;;    (-> builder-category :marker :value count (> 0))))