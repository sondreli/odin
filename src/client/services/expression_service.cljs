(ns client.services.expression-service
  (:require [clojure.string :as s]))

;; Tokenizer: splits expression string into tokens
;; Tokens: :number, :variable, :op, :lparen, :rparen

(defn- tokenize [expr-str]
  (loop [s (s/trim expr-str)
         tokens []]
    (if (empty? s)
      tokens
      (let [ch (first s)]
        (cond
          (#{\space \tab} ch)
          (recur (subs s 1) tokens)

          (#{\+ \- \* \/} ch)
          (recur (subs s 1) (conj tokens {:type :op :value (str ch)}))

          (= ch \()
          (recur (subs s 1) (conj tokens {:type :lparen}))

          (= ch \))
          (recur (subs s 1) (conj tokens {:type :rparen}))

          (or (js/isFinite (str ch)) (= ch \.))
          (let [num-str (re-find #"^[\d.]+" s)]
            (recur (subs s (count num-str))
                   (conj tokens {:type :number :value (js/parseFloat num-str)})))

          :else
          (let [var-match (re-find #"^[a-zA-ZæøåÆØÅ_][a-zA-ZæøåÆØÅ0-9_:]*(?:-[a-zA-ZæøåÆØÅ0-9_:]+)*(?: [a-zA-ZæøåÆØÅ_][a-zA-ZæøåÆØÅ0-9_:]*(?:-[a-zA-ZæøåÆØÅ0-9_:]+)*)*" s)]
            (if var-match
              (recur (subs s (count var-match))
                     (conj tokens {:type :variable :value var-match}))
              {:error (str "Unexpected character: " ch)})))))))

;; Recursive descent parser
;; Grammar:
;;   expr     -> term (('+' | '-') term)*
;;   term     -> factor (('*' | '/') factor)*
;;   factor   -> NUMBER | VARIABLE | '(' expr ')' | ('+' | '-') factor

(declare parse-term parse-factor)

(defn- parse-expr [tokens pos]
  (let [[left new-pos] (parse-term tokens pos)]
    (if (or (:error left) (nil? left))
      [left new-pos]
      (loop [node left
             p new-pos]
        (let [tok (get tokens p)]
          (if (and tok (= (:type tok) :op) (#{"+" "-"} (:value tok)))
            (let [[right rp] (parse-term tokens (inc p))]
              (if (:error right)
                [right rp]
                (recur {:type :binop :op (:value tok) :left node :right right}
                       rp)))
            [node p]))))))

(defn- parse-term [tokens pos]
  (let [[left new-pos] (parse-factor tokens pos)]
    (if (or (:error left) (nil? left))
      [left new-pos]
      (loop [node left
             p new-pos]
        (let [tok (get tokens p)]
          (if (and tok (= (:type tok) :op) (#{"*" "/"} (:value tok)))
            (let [[right rp] (parse-factor tokens (inc p))]
              (if (:error right)
                [right rp]
                (recur {:type :binop :op (:value tok) :left node :right right}
                       rp)))
            [node p]))))))

(defn- parse-factor [tokens pos]
  (let [tok (get tokens pos)]
    (cond
      (nil? tok)
      [{:error "Unexpected end of expression"} pos]

      (= (:type tok) :number)
      [{:type :number :value (:value tok)} (inc pos)]

      (= (:type tok) :variable)
      [{:type :variable :value (:value tok)} (inc pos)]

      (= (:type tok) :lparen)
      (let [[node new-pos] (parse-expr tokens (inc pos))
            close-tok (get tokens new-pos)]
        (if (and close-tok (= (:type close-tok) :rparen))
          [node (inc new-pos)]
          [{:error "Missing closing parenthesis"} new-pos]))

      (and (= (:type tok) :op) (#{"+" "-"} (:value tok)))
      (let [[node new-pos] (parse-factor tokens (inc pos))]
        (if (:error node)
          [node new-pos]
          [{:type :unary :op (:value tok) :operand node} new-pos]))

      :else
      [{:error (str "Unexpected token: " (pr-str tok))} pos])))

(defn parse-expression
  "Parses an expression string into an AST.
   Returns {:ast ...} on success or {:error ...} on failure."
  [expr-str]
  (if (or (nil? expr-str) (s/blank? expr-str))
    {:error "Empty expression"}
    (let [tokens (tokenize expr-str)]
      (if (:error tokens)
        tokens
        (let [[ast final-pos] (parse-expr tokens 0)]
          (cond
            (:error ast) ast
            (< final-pos (count tokens)) {:error "Unexpected tokens after expression"}
            :else {:ast ast}))))))

(defn evaluate
  "Evaluates an AST with the given variable map {name -> number}.
   Returns a number or nil if a variable is missing."
  [ast variable-map]
  (case (:type ast)
    :number (:value ast)
    :variable (get variable-map (:value ast) 0)
    :unary (let [val (evaluate (:operand ast) variable-map)]
             (case (:op ast)
               "+" val
               "-" (- val)))
    :binop (let [l (evaluate (:left ast) variable-map)
                 r (evaluate (:right ast) variable-map)]
             (case (:op ast)
               "+" (+ l r)
               "-" (- l r)
               "*" (* l r)
               "/" (if (zero? r) 0 (/ l r))))
    0))

(defn extract-variables
  "Walks the AST and returns a vector of all variable names used (may contain duplicates)."
  [ast]
  (case (:type ast)
    :variable [(:value ast)]
    :binop (into (extract-variables (:left ast))
                 (extract-variables (:right ast)))
    :unary (extract-variables (:operand ast))
    :number []
    []))

(defn expression-to-string
  "Renders the expression with variable names replaced by their values from var-map."
  [ast var-map]
  (case (:type ast)
    :number (str (:value ast))
    :variable (let [v (get var-map (:value ast) 0)]
                (str (:value ast) " (" (.toFixed v 0) ")"))
    :unary (str (:op ast) (expression-to-string (:operand ast) var-map))
    :binop (str (expression-to-string (:left ast) var-map)
                " " (:op ast) " "
                (expression-to-string (:right ast) var-map))
    ""))

(defn build-variable-map
  "Given a set of transactions for a single time slot, categories, and tags,
   builds a map of {variable-name -> numeric-value}.
   Category and tag amounts are absolute values.
   Tags are additive: a transaction can count toward multiple tag variables."
  ([transactions categories]
   (build-variable-map transactions categories []))
  ([transactions categories tags]
   (let [cat-map (into {} (map (juxt :id identity) categories))
         tag-map (into {} (map (juxt :id identity) tags))
         cat-amounts (reduce
                      (fn [acc txn]
                        (let [cat-id (:category-id txn)
                              amt (:amount txn)]
                          (if cat-id
                            (update acc cat-id (fnil + 0) amt)
                            acc)))
                      {}
                      transactions)
         tag-amounts (reduce
                      (fn [acc txn]
                        (let [tag-ids (or (:tag-ids txn) [])
                              amt (:amount txn)]
                          (reduce (fn [a tid] (update a tid (fnil + 0) amt))
                                  acc
                                  tag-ids)))
                      {}
                      transactions)
         all-positive (->> transactions (map :amount) (filter pos?) (reduce + 0))
         all-negative (->> transactions (map :amount) (filter neg?) (reduce + 0))
         base-map (-> (reduce-kv
                       (fn [m cat-id amount]
                         (let [cat-name (-> cat-map (get cat-id) :name)]
                           (if cat-name
                             (assoc m cat-name (Math/abs amount))
                             m)))
                       {}
                       cat-amounts)
                      (assoc "alle-inntekter" all-positive)
                      (assoc "alle-utgifter" (Math/abs all-negative)))]
     (reduce-kv
      (fn [m tag-id amount]
        (let [tag-name (-> tag-map (get tag-id) :name)]
          (if tag-name
            (assoc m (str "tag:" tag-name) (Math/abs amount))
            m)))
      base-map
      tag-amounts))))

(defn available-variables
  "Returns a vector of available variable names for the expression builder.
   Includes categories and tags (prefixed with 'tag:')."
  ([categories]
   (available-variables categories []))
  ([categories tags]
   (let [cat-names (->> categories
                        (filter :name)
                        (remove #(#{"in" "out" "ukategorisert-in" "ukategorisert-out"} (:id %)))
                        (map :name)
                        (sort))
         tag-names (->> tags
                        (filter :name)
                        (map #(str "tag:" (:name %)))
                        (sort))]
     (into (into ["alle-inntekter" "alle-utgifter"] cat-names) tag-names))))
