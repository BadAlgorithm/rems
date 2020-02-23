(ns rems.common.util
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; TODO remove separate clj and cljs implementations of getx and getx-in
(defn getx
  "Like `get` but throws an exception if the key is not found."
  [m k]
  (let [e (get m k ::sentinel)]
    (if-not (= e ::sentinel)
      e
      (throw (ex-info "Missing required key" {:map m :key k})))))

(defn getx-in
  "Like `get-in` but throws an exception if the key is not found."
  [m ks]
  (reduce getx m ks))

(defn select-vals
  "Select values in map `m` specified by given keys `ks`.

  Values will be returned in the order specified by `ks`.

  You can specify a `default-value` that will be used if the
  key is not found in the map. This is like `get` function."
  [m ks & [default-value]]
  (vec (reduce #(conj %1 (get m %2 default-value)) [] ks)))

(defn build-index
  "Index the `coll` with given keys `ks` and map values with given `f`.

  Results ia nested map, `(count ks)` levels deep, e.g.
    (build-index [:a :b] :c [{:a 1 :b \"x\" :c :a} {:a 1 :b \"y\" :c :b}])
      ==> {1 {\"x\" :a
              \"y\" :b}}

  In case of non-unique keys, `build-index` picks the first value, e.g.

    (build-index [:a] identity [{:a 1 :b \"x\"} {:a 1 :b \"y\"}])
      ==> {1 {:a 1 :b \"x\"}}"
  [ks f coll]
  (if (empty? ks)
    (f (first coll))
    (->> coll
         (group-by (first ks))
         (map (fn [[k v]] [k (build-index (rest ks) f v)]))
         (into {}))))

(deftest test-build-index
  (is (= {1 {"x" :a "y" :b}}
         (build-index [:a :b] :c [{:a 1 :b "x" :c :a} {:a 1 :b "y" :c :b}])))
  (is (= {1 {:a 1 :b "x" :c :a}}
         (build-index [:a] identity [{:a 1 :b "x" :c :a} {:a 1 :b "y" :c :b}]))))

(defn index-by
  "Index the collection coll with given keys `ks`.
  Result is a nested map, `(count ks)` levels deep, e.g.

    (index-by [:a :b] [{:a 1 :b \"x\"} {:a 1 :b \"y\"}])
      ==> {1 {\"x\" {:a 1 :b \"x\"}
              \"y\" {:a 1 :b \"y\"}}}

  In case of non-unique keys, index-by picks the first value, e.g.

    (index-by [:a] [{:a 1 :b \"x\"} {:a 1 :b \"y\"}])
      ==> {1 {:a 1 :b \"x\"}}"
  [ks coll]
  (build-index ks identity coll))

(deftest test-index-by
  (is (= {1 {"x" {:a 1 :b "x"}
             "y" {:a 1 :b "y"}}}
         (index-by [:a :b] [{:a 1 :b "x"} {:a 1 :b "y"}])))
  (is (= {false 1 true 2}
         (index-by [even?] [1 2 3 4]))))

(defn distinct-by
  "Remove duplicates from sequence, comparing the value returned by key-fn.
   The first element that key-fn returns a given value for is retained.

   Order of sequence is not preserved in any way."
  [key-fn sequence]
  (map first (vals (group-by key-fn sequence))))

(defn andstr
  "Like `apply str coll` but only produces something if all the
  values are truthy like with `and`.

  Useful for statements like
  ```clj
  (str (andstr (:foo x) \"/\") (:bar y))
  ```
  See also `test-andstr` for examples."
  [& coll]
  (when (every? identity coll)
    (apply str coll)))

(deftest test-andstr
  (testing "when any argument is falsey the result is nil"
    (is (= nil (andstr nil 1 2 3)))
    (is (= nil (andstr 1 2 false 3))))
  (testing "when all arguments are truthy the results are concatenated"
    (let [x {:foo 2}]
      (is (= "2/" (andstr (:foo x) "/")))
      (is (= "(2)" (andstr "(" (:foo x) ")"))))))

(defn deep-merge
  "Recursively merges maps and sequentials so that the values in `b`
  will replace the values at the same key or index in `a`."
  [a b]
  (cond (and (sequential? a) (sequential? b))
        (let [max-length (max (count a) (count b))
              a (take max-length (concat a (repeat nil)))
              b (take max-length (concat b (repeat nil)))]
          (doall (map deep-merge a b)))

        (map? a)
        (merge-with deep-merge a b)

        :else b))

(deftest test-deep-merge
  (testing "merge nil"
    (is (= nil
           (deep-merge nil
                       nil)))
    (is (= {:a 1}
           (deep-merge nil
                       {:a 1})))
    (is (= {:a 1}
           (deep-merge {:a 1}
                       nil))))
  (testing "preserve false"
    (is (= {:b false}
           (deep-merge {:b :anything}
                       {:b false}))))
  (testing "merge maps"
    (is (= {:a 2}
           (deep-merge {:a 1}
                       {:a 2})))
    (is (= {:a 1 :b 2 :c 2}
           (deep-merge {:a 1 :b 1}
                       {:b 2 :c 2})))
    (is (= {:a {:b {:c 100 :d 2}}}
           (deep-merge {:a {:b {:c 1 :d 2}}}
                       {:a {:b {:c 100}}}))))
  (testing "merge vectors"
    (is (= [{:a 2}]
           (deep-merge [{:a 1}]
                       [{:a 2}])))
    (is (= [{:a 1 :b 2 :c 2}]
           (deep-merge [{:a 1 :b 1}]
                       [{:b 2 :c 2}])))
    (is (= [{:a 1} {:b 2}]
           (deep-merge [{:a 1}]
                       [nil {:b 2}])))
    (is (= [{:b 2} {:a 1}]
           (deep-merge [nil {:a 1}]
                       [{:b 2}]))))
  (testing "merge lists"
    (is (= [{:a 2}]
           (deep-merge '({:a 1})
                       '({:a 2}))))
    (is (= [{:a 1 :b 2 :c 2}]
           (deep-merge '({:a 1 :b 1})
                       '({:b 2 :c 2}))))))

(defn recursive-keys [m]
  (mapcat (fn [[k v]]
            (if (map? v)
              (map (partial cons k) (recursive-keys v))
              [(list k)]))
          m))

(deftest test-recursive-keys
  (is (= [[:a] [:b]] (recursive-keys {:a [1] :b "foo"})))
  (is (= [[:a :b] [:a :c] [:a :d :e] [:a :d :f]]
         (recursive-keys {:a {:b 1 :c nil :d {:e "foo" :f [3]}}}))))

(defn parse-int [s]
  #?(:clj (try
            (when s
              (java.lang.Integer/parseInt s))
            (catch NumberFormatException e
              nil))
     :cljs (let [x (js/parseInt s)]
             (when-not (js/isNaN x)
               x))))


(deftest test-parse-int
  (is (= nil (parse-int nil)))
  (is (= nil (parse-int "")))
  (is (= nil (parse-int "a")))
  (is (= 7 (parse-int "7"))))

(defn remove-empty-keys
  "Given a map, recursively remove keys with empty map or nil values.

  E.g., given {:a {:b {:c nil} :d {:e :f}}}, return {:a {:d {:e :f}}}."
  [m]
  (into {} (filter (fn [[_ v]] (not ((if (map? v) empty? nil?) v)))
                   (mapv (fn [[k v]] [k (if (map? v)
                                          (remove-empty-keys v)
                                          v)])
                         m))))

(deftest test-remove-empty-keys
  (is (= (remove-empty-keys {}) {}))
  (is (= (remove-empty-keys {:a :b}) {:a :b}))
  (is (= (remove-empty-keys {:a nil}) {}))
  (is (= (remove-empty-keys {:a {:b {:c nil} :d {:e :f}}}) {:a {:d {:e :f}}})))

(defn normalize-file-path
  "A file path may contain local filesystem parts that we want to remove
  so that we can use the path to refer to e.g. project GitHub."
  [path]
  (str/replace (subs path (str/index-of path "src"))
               "\\" "/"))

(deftest normalize-file-path-test
  (is (= "src/foo/bar.clj" (normalize-file-path "/home/john/rems/src/foo/bar.clj")))
  (is (= "src/foo/bar.clj" (normalize-file-path "C:\\Users\\john\\rems\\src\\foo/bar.clj"))))
