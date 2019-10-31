(ns com.wsscode.pathom.connect.run-graph-readers-test
  (:require [clojure.test :refer :all]
            [com.wsscode.pathom.connect :as pc]
            [com.wsscode.pathom.sugar :as ps]
            [com.wsscode.pathom.core :as p]))

(defn run-parser [{::keys [resolvers query]}]
  (let [parser (ps/connect-serial-parser
                 {::ps/connect-reader pc/reader3}
                 resolvers)]
    (parser {} query)))

(deftest test-runner3
  (testing "single attribute"
    (is (= (run-parser
             {::resolvers [(pc/constantly-resolver :a 42)]
              ::query     [:a]})
           {:a 42}))

    (testing "missed output"
      (is (= (run-parser
               {::resolvers [(pc/resolver 'a
                               {::pc/output [:a]}
                               (fn [_ _] {}))]
                ::query     [:a]})
             {:a ::p/not-found})))

    (testing "resolver error"
      (is (= (run-parser
               {::resolvers [(pc/resolver 'a
                               {::pc/output [:a]}
                               (fn [_ _] (throw (ex-info "Error" {:error "detail"}))))]
                ::query     [:a]})
             {:a                              :com.wsscode.pathom.core/reader-error
              :com.wsscode.pathom.core/errors {[:a] "class clojure.lang.ExceptionInfo: Error - {:error \"detail\"}"}}))))

  (testing "multiple attributes"
    (is (= (run-parser
             {::resolvers [(pc/resolver 'a
                             {::pc/output [:a :b]}
                             (fn [_ _] {:a 42 :b "foo"}))]
              ::query     [:a :b]})
           {:a 42
            :b "foo"}))))