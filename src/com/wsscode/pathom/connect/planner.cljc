(ns com.wsscode.pathom.connect.planner
  (:require [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [com.fulcrologic.guardrails.core :refer [>def >defn >fdef => | <- ?]]
            [com.wsscode.pathom.connect.indexes :as pci]
            [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.misc :as p.misc]
            [edn-query-language.core :as eql]))

(>def ::node-id pos-int?)
(>def ::graph (s/keys :req [::nodes]))
(>def ::available-data :com.wsscode.pathom.connect/io-map)
(>def ::after-nodes (s/coll-of ::node-id :kind set?))
(>def ::attr-deps-trail :com.wsscode.pathom.connect/attributes-set)
(>def ::branch-type #{::run-or ::run-and})
(>def ::dynamic-nodes-visited (s/coll-of ::node-id :kind set?))
(>def ::dynamic-resolvers (s/coll-of :com.wsscode.pathom.connect/sym :kind set?))
(>def ::input :com.wsscode.pathom.connect/io-map)
(>def ::index-attrs (s/map-of :com.wsscode.pathom.connect/attribute ::node-id))
(>def ::index-syms (s/map-of :com.wsscode.pathom.connect/sym (s/keys :req [::node-id])))
(>def ::nodes (s/map-of ::node-id (s/keys :req [::node-id])))
(>def ::previous-graph ::graph)
(>def ::source-for-attrs :com.wsscode.pathom.connect/attributes-set)
(>def ::requires :com.wsscode.pathom.connect/io-map)
(>def ::root ::node-id)
(>def ::run-and (s/coll-of ::node-id :kind vector?))
(>def ::run-next ::node-id)
(>def ::run-next-trail (s/coll-of :com.wsscode.pathom.connect/sym :kind set?))
(>def ::run-or (s/coll-of ::node-id :kind vector?))
(>def ::source-sym :com.wsscode.pathom.connect/sym)
(>def ::unreachable-attrs :com.wsscode.pathom.connect/attributes-set)
(>def ::unreachable-syms (s/coll-of :com.wsscode.pathom.connect/sym :kind set?))

(p.misc/spec-doc ::after-nodes "A node-id that points to the node before the current node. In regular execution nodes, this is the reverse of ::run-next, but in case of immediate children of branch nodes, this points to the branch node.")
(p.misc/spec-doc ::available-data "An IO-MAP style declaring which data is already available when the planner starts.")
(p.misc/spec-doc ::attr-deps-trail "A set containing attributes already in consideration when computing dependencies.")
(p.misc/spec-doc ::branch-type "A set containing attributes already in consideration when computing dependencies.")
(p.misc/spec-doc ::dynamic-nodes-visited "A set containing node ids of dynamic nodes visited during dynamic node optimization.")
(p.misc/spec-doc ::dynamic-resolvers "A set containing symbols of dynamic resolvers used in the graph.")
(p.misc/spec-doc ::id-counter "An atom with a number, used to get the next node-id when creating new nodes.")
(p.misc/spec-doc ::input "An IO-MAP description of required inputs to run the node.")
(p.misc/spec-doc ::index-attrs "A index pointing from attribute to the node that provides its value.")
(p.misc/spec-doc ::index-syms "An index from resolver symbol to a set of execution nodes where its used.")
(p.misc/spec-doc ::node-id "ID for a execution node in the planner graph.")
(p.misc/spec-doc ::nodes "The nodes index.")
(p.misc/spec-doc ::previous-graph "Graph before modifications, this is used to restore previous graph when some path ends up being unreachable.")
(p.misc/spec-doc ::source-for-attrs "Set of attributes that are provided by this node.")
(p.misc/spec-doc ::requires "An IO-MAP description of what is required from this execution node to returns.")
(p.misc/spec-doc ::root "A node-id that defines the root in the planner graph.")
(p.misc/spec-doc ::run-and "Vector containing nodes ids to run in a AND branch.")
(p.misc/spec-doc ::run-next "A node-id that points to the next node to run.")
(p.misc/spec-doc ::run-next-trail "A set containing node ids already in consideration when computing dependencies.")
(p.misc/spec-doc ::run-or "Vector containing nodes ids to run in a AND branch.")
(p.misc/spec-doc ::source-sym "On dynamic resolvers, this points to the original source resolver in the foreign parser.")
(p.misc/spec-doc ::unreachable-attrs "A set containing the attributes that can't be reached considering current graph and available data.")
(p.misc/spec-doc ::unreachable-syms "A set containing the resolvers that can't be reached considering current graph and available data.")

(def pc-sym :com.wsscode.pathom.connect/sym)
(def pc-dyn-sym :com.wsscode.pathom.connect/dynamic-sym)
(def pc-output :com.wsscode.pathom.connect/output)
(def pc-provides :com.wsscode.pathom.connect/provides)
(def pc-attr :com.wsscode.pathom.connect/attribute)
(def pc-input :com.wsscode.pathom.connect/input)

(def ast-node :edn-query-language.ast/node)

(defn merge-io
  ([a] a)
  ([a b]
   (pci/merge-io a b)))

(declare compute-run-graph* compute-root-and)

(defn base-graph []
  {::nodes             {}
   ::index-syms        {}
   ::unreachable-syms  #{}
   ::unreachable-attrs #{}})

(defn base-env []
  {::id-counter     (atom 0)
   ::available-data {}})

(defn next-node-id [{::keys [id-counter]}]
  (swap! id-counter inc))

(defn get-node
  [graph node-id]
  (get-in graph [::nodes node-id]))

(defn assoc-node
  [graph node-id k v]
  (if (get-node graph node-id)
    (assoc-in graph [::nodes node-id k] v)
    graph))

(defn update-node
  ([graph node-id k f]
   (if (get-node graph node-id)
     (update-in graph [::nodes node-id k] f)
     graph))
  ([graph node-id k f v]
   (if (get-node graph node-id)
     (update-in graph [::nodes node-id k] f v)
     graph))
  ([graph node-id k f v v2]
   (if (get-node graph node-id)
     (update-in graph [::nodes node-id k] f v v2)
     graph))
  ([graph node-id k f v v2 v3]
   (if (get-node graph node-id)
     (update-in graph [::nodes node-id k] f v v2 v3)
     graph))
  ([graph node-id k f v v2 v3 & args]
   (if (get-node graph node-id)
     (apply update-in graph [::nodes node-id k] f v v2 v3 args)
     graph)))

(defn get-root-node [{::keys [root] :as graph}]
  (get-node graph root))

(defn set-root-node [graph node-id]
  (assoc graph ::root node-id))

(defn branch-node? [node]
  (or (contains? node ::run-and)
      (contains? node ::run-or)))

(defn node-kind [node]
  (cond
    (pc-sym node)
    ::node-resolver

    (::run-and node)
    ::node-and

    (::run-or node)
    ::node-or

    :else
    ::node-unknown))

(defn dynamic-resolver?
  [{:com.wsscode.pathom.connect/keys [index-resolvers]} sym]
  (get-in index-resolvers [sym :com.wsscode.pathom.connect/dynamic-resolver?]))

(defn add-unreachable-attr
  "Add attribute to unreachable list"
  [graph attr]
  (update graph ::unreachable-attrs conj attr))

(defn optimize-merge?
  "Check if node and graph point to same run-next."
  [graph {::keys [node-id]}]
  (let [root-next (get-in graph [::nodes (::root graph) ::run-next])
        node-next (get-in graph [::nodes node-id ::run-next])]
    (and root-next (= root-next node-next))))

(defn resolver-provides
  "Get resolver provides from environment source symbol."
  [{:com.wsscode.pathom.connect/keys [index-resolvers]
    ::keys                           [source-sym]}]
  (pci/resolver-provides (get index-resolvers source-sym)))

(defn node-source-sym [node]
  (or (::source-sym node) (pc-sym node)))

(defn find-dynamic-node-to-merge
  "Given some branch node, tries to find a node with a dynamic resolver that's the
  same sym as the node in node-id."
  [{::keys [root] :as graph}
   {::keys [branch-type]
    :as    env}
   {::keys [node-id]}]
  (let [node     (get-in graph [::nodes node-id])
        node-sym (pc-sym node)]
    (if (dynamic-resolver? env node-sym)
      (some #(if (= node-sym (get-in graph [::nodes % pc-sym])) %)
        (get-in graph [::nodes root branch-type])))))

(defn find-branch-node-to-merge
  "Given some branch node, tries to find a node with a dynamic resolver that's the
  same sym as the node in node-id."
  [{::keys [root] :as graph}
   {::keys [branch-type]}
   {::keys [node-id]}]
  (let [node     (get-in graph [::nodes node-id])
        node-sym (pc-sym node)]
    (if node-sym
      (some #(if (= node-sym (get-in graph [::nodes % pc-sym])) %)
        (get-in graph [::nodes root branch-type])))))

(defn add-after-node [graph node-id after-node-id]
  (update-node graph node-id ::after-nodes p.misc/sconj after-node-id))

(defn set-after-node [graph node-id after-node-id]
  (assoc-node graph node-id ::after-nodes #{after-node-id}))

(defn remove-after-node [graph node-id after-node-id]
  (let [node         (get-node graph node-id)
        after-nodes' (disj (::after-nodes node #{}) after-node-id)]
    (if (seq after-nodes')
      (assoc-node graph node-id ::after-nodes after-nodes')
      (if node
        (update-in graph [::nodes node-id] dissoc ::after-nodes)
        graph))))

(defn set-node-run-next [graph target-node-id run-next]
  (if run-next
    (-> graph
        (assoc-node target-node-id ::run-next run-next)
        (add-after-node run-next target-node-id))
    graph))

(defn simplify-branch
  "If you pass a branch node with a single branch item, it removes the branch node
  from the graph and puts that single item on its place."
  [{::keys [root] :as graph}
   {::keys [branch-type] :as env}]
  (let [{::keys [run-next] :as root-node} (get-root-node graph)
        items (get root-node branch-type)]
    (if (= 1 (count items))
      (let [item-node-id (first items)
            item-node    (get-node graph item-node-id)]
        (-> graph
            (update ::nodes dissoc root)
            (update-in [::nodes item-node-id] dissoc ::after-nodes)
            (cond->
              run-next
              (-> (add-after-node run-next item-node-id)
                  (as-> <>
                    (compute-root-and (assoc <> ::root run-next) env {::node-id (::run-next item-node)})
                    (assoc-in <> [::nodes item-node-id ::run-next] (::root <>)))))
            (assoc ::root item-node-id)))
      graph)))

(defn remove-node
  "Remove a node from the graph. In case of resolver nodes it also removes them
  from the ::index-syms."
  [graph {::keys [node-id]}]
  (let [{::keys [run-next] :as node} (get-node graph node-id)]
    (-> graph
        (update-in [::index-syms (pc-sym node)] disj node-id)
        (remove-after-node run-next node-id)
        (update ::nodes dissoc node-id))))

(defn merge-node-requires
  "Merge requires from node into target-node-id."
  [graph target-node-id {::keys [requires]}]
  (update-in graph [::nodes target-node-id ::requires] merge-io requires))

(defn merge-node-input
  "Merge input from node into target-node-id."
  [graph target-node-id {::keys [input]}]
  (update-in graph [::nodes target-node-id ::input] merge-io input))

(defn update-node-run-next
  "Update the node-id run-next value, if run-next is nil the property
  will be removed from the map."
  [graph node-id run-next]
  (if run-next
    (assoc-in graph [::nodes node-id ::run-next] run-next)
    (update-in graph [::nodes node-id] dissoc ::run-next)))

(defn merge-nodes-run-next
  "Updates node-id run-next with the run-next of the last element. This will do an AND
  branch operation with node-id run-next and run-next, updating the reference of node-id
  run-next."
  [graph env target-node-id {::keys [run-next]}]
  (let [root            (::root graph)
        merge-into-node (get-node graph target-node-id)]
    (-> graph
        (assoc ::root (::run-next merge-into-node))
        (compute-root-and env {::node-id run-next})
        (add-after-node run-next target-node-id)
        (as-> <> (update-node-run-next <> target-node-id (::root <>)))
        (assoc ::root root))))

(defn transfer-node-source-attrs
  "Pulls source for attributes from node to target-node-id, also updates the attributes
  index to respect the transfer."
  [graph target-node-id {::keys [source-for-attrs]}]
  (if source-for-attrs
    (-> (update-in graph [::nodes target-node-id ::source-for-attrs] (fnil into #{}) source-for-attrs)
        (as-> <>
          (reduce
            #(assoc-in % [::index-attrs %2] target-node-id)
            <>
            source-for-attrs)))
    graph))

(defn collapse-nodes-branch
  [graph env node-id collapse-node-id]
  (let [node (get-node graph node-id)]
    (-> graph
        (merge-node-requires collapse-node-id node)
        (merge-node-input collapse-node-id node)
        (merge-nodes-run-next env collapse-node-id node)
        (remove-node node)
        (transfer-node-source-attrs collapse-node-id node))))

(defn add-branch-node
  "Given a branch node is the root, this function will add the new node as part
  of that branch node. If the node is a repeating dynamic node it will cause the new node
  to be collapsed into the already existent dynamic node."
  [{::keys [root] :as graph}
   {::keys [branch-type] :as env}
   {::keys [node-id]}]
  (if-not node-id
    (println "Trying to add nil branch node"))
  (let [node (get-node graph node-id)]
    (if-let [collapse-node-id (find-branch-node-to-merge graph env node)]
      (cond-> (collapse-nodes-branch graph env node-id collapse-node-id)
        (= branch-type ::run-and)
        (merge-node-requires root (get-node graph node-id)))
      (-> graph
          (update-in [::nodes root branch-type] conj node-id)
          (set-after-node node-id root)
          (cond->
            (= branch-type ::run-and)
            (merge-node-requires root node)

            (optimize-merge? graph node)
            (update-in [::nodes node-id] dissoc ::run-next))))))

(defn create-branch-node
  [{::keys [root] :as graph} env node branch-node]
  (let [root-next      (get-in graph [::nodes root ::run-next])
        optimize-next? (optimize-merge? graph node)
        branch-node    (cond-> branch-node
                         optimize-next?
                         (assoc ::run-next root-next))
        branch-node-id (::node-id branch-node)]
    (-> graph
        (assoc-in [::nodes branch-node-id] branch-node)
        (add-after-node root branch-node-id)
        (cond-> optimize-next?
                (-> (update-in [::nodes root] dissoc ::run-next)
                    (set-after-node root-next branch-node-id)))
        (assoc ::root branch-node-id)
        (add-branch-node env node))))

(defn compute-root-branch
  [graph
   {::keys [branch-type] :as env}
   {::keys [node-id]}
   branch-node-factory]
  (if node-id
    (let [root-node (get-root-node graph)
          next-node (get-node graph node-id)
          root-sym  (pc-sym root-node)
          next-sym  (pc-sym next-node)]
      (cond
        ; skip, no next node
        (not next-node)
        graph

        (not root-node)
        (set-root-node graph node-id)

        (and root-sym
             (= root-sym next-sym))
        (-> (collapse-nodes-branch graph env (::root graph) node-id)
            (set-root-node node-id))

        (and (get next-node branch-type)
             #_(= (::run-next next-node) (::run-next root-node))
             root-sym)
        (add-branch-node (assoc graph ::root node-id) env root-node)

        (and (get root-node branch-type)
             #_(= (::run-next next-node) (::run-next root-node))
             next-sym)
        (add-branch-node graph env next-node)

        #_(and (get next-node ::run-and) (get root-node ::run-and))


        :else
        (create-branch-node graph env next-node (branch-node-factory))))
    graph))

(defn compute-root-or
  [{::keys [root] :as graph}
   {:com.wsscode.pathom.connect/keys [attribute] :as env}
   {::keys [node-id] :as node}]
  (if (= root node-id)
    graph
    (compute-root-branch graph (assoc env ::branch-type ::run-or) node
      (fn []
        {::node-id  (next-node-id env)
         ::requires {attribute {}}
         ::run-or   [(::root graph)]}))))

(defn compute-root-and
  [{::keys [root] :as graph} env {::keys [node-id] :as node}]
  (if (= root node-id)
    graph
    (compute-root-branch graph (assoc env ::branch-type ::run-and) node
      (fn []
        (let [{::keys [requires]} (get-root-node graph)]
          {::node-id  (next-node-id env)
           ::requires requires
           ::run-and  [(::root graph)]})))))

(def dynamic-base-provider-sym `run-graph-base-provider)

(defn inject-index-nested-provides
  [indexes
   {:com.wsscode.pathom.connect/keys [attribute sym]
    :as                              env}]
  (let [sym-provides    (or (resolver-provides env) {attribute {}})
        nested-provides (get sym-provides attribute)]
    (-> indexes
        (assoc-in [:com.wsscode.pathom.connect/index-resolvers
                   dynamic-base-provider-sym]
          {pc-sym      dynamic-base-provider-sym
           pc-dyn-sym  sym
           pc-provides nested-provides})
        (update :com.wsscode.pathom.connect/index-oir
          (fn [oir]
            (reduce
              (fn [oir attr]
                (update-in oir [attr #{}] p.misc/sconj dynamic-base-provider-sym))
              oir
              (keys nested-provides)))))))

(defn compute-nested-requires
  [{ast :edn-query-language.ast/node
    :as env}]
  (let [sub-graph (compute-run-graph*
                    (base-graph)
                    (-> (base-env)
                        (merge (select-keys env [:com.wsscode.pathom.connect/index-resolvers
                                                 :com.wsscode.pathom.connect/index-oir]))
                        (inject-index-nested-provides env)
                        (assoc ast-node ast)))]
    (-> sub-graph get-root-node ::requires (or {}))))

(defn create-resolver-node
  "Create a new node representative to run a given resolver."
  [graph
   {::keys                           [run-next input source-sym]
    :com.wsscode.pathom.connect/keys [attribute sym]
    ast                              :edn-query-language.ast/node
    :as                              env}]
  (let [requires     (if (and (seq (:children ast))
                              (dynamic-resolver? env sym))
                       {attribute (compute-nested-requires env)}
                       {attribute {}})
        next-node    (get-node graph run-next)]
    (if (and (dynamic-resolver? env sym)
             (= sym (pc-sym next-node)))
      (-> next-node
          (update ::requires merge-io requires)
          (assoc ::input input))
      (cond->
        {pc-sym     sym
         ::node-id  (next-node-id env)
         ::requires requires
         ::input    input}

        (not= sym source-sym)
        (assoc ::source-sym source-sym)

        run-next
        (assoc ::run-next run-next)))))

(defn extend-node-run-next
  "Extend node run next, adding the current run-next from env to it. If the node don't
  have a run-next, it will be set for the one informed by the env. In case
  there is already something to run next the node, a wrap AND branch node will be
  inserted containing the previous node and the new one."
  [graph
   {::keys [run-next run-next-trail] :as env}
   extend-node-id]
  (if (or (not run-next)
          (contains? run-next-trail extend-node-id))
    graph
    (let [node      (get-in graph [::nodes extend-node-id])
          new-graph (compute-root-and
                      (assoc graph ::root run-next)
                      env
                      {::node-id (::run-next node)})]
      (-> graph
          (assoc
            ::nodes (::nodes new-graph)
            ::index-syms (::index-syms new-graph)
            ::index-attrs (::index-attrs new-graph))
          (assoc-in [::nodes extend-node-id ::run-next] (::root new-graph))
          (add-after-node (::root new-graph) extend-node-id)))))

(defn include-node [graph env {::keys [node-id] :as node}]
  (let [sym (pc-sym node)]
    (-> graph
        (assoc-in [::nodes node-id] node)
        (cond->
          sym
          (update-in [::index-syms sym] p.misc/sconj node-id)

          (and sym (dynamic-resolver? env sym))
          (update ::dynamic-resolvers p.misc/sconj sym)))))

(defn node-branches [node]
  (or (::run-or node)
      (::run-and node)))

(defn collect-syms
  ([graph env node] (collect-syms graph env node #{}))
  ([graph
    env
    {::keys [node-id]} syms]
   (let [node (get-in graph [::nodes node-id])]
     (if-let [sym (pc-sym node)]
       (if (dynamic-resolver? env sym)
         syms
         (conj syms sym))
       (into syms (mapcat #(collect-syms graph env {::node-id %}) (node-branches node)))))))

(defn all-attribute-resolvers
  [{:com.wsscode.pathom.connect/keys [index-oir]}
   attr]
  (if-let [ir (get index-oir attr)]
    (into #{} cat (vals ir))
    #{}))

(defn mark-node-unreachable
  [previous-graph
   graph
   {::keys [unreachable-syms
            unreachable-attrs]}
   env]
  (let [syms (->> (collect-syms graph env (get-root-node graph))
                  (into unreachable-syms)
                  (into (::unreachable-syms previous-graph)))]
    (cond-> (assoc previous-graph
              ::unreachable-syms syms
              ::unreachable-attrs unreachable-attrs)
      (set/subset? (all-attribute-resolvers env (pc-attr env)) syms)
      (update ::unreachable-attrs conj (pc-attr env)))))

(defn compute-missing-chain
  "Start a recursive call to process the dependencies required by the resolver. It
  sets the ::run-next data at the env, it will be used to link the nodes after they
  are created in the process."
  [graph {::keys [previous-graph] :as env} missing]
  (if (seq missing)
    (let [{::keys [index-attrs] :as graph'}
          (compute-run-graph*
            (dissoc graph ::root)
            (-> env
                (dissoc pc-attr)
                (update ::run-next-trail p.misc/sconj (::root graph))
                (update ::attr-deps-trail p.misc/sconj (pc-attr env))
                (assoc ast-node (eql/query->ast (vec missing))
                  ::run-next (::root graph))))]

      (let [still-missing (remove (or index-attrs {}) missing)
            all-provided? (not (seq still-missing))]
        (if all-provided?
          graph'
          (let [{::keys [unreachable-syms] :as out'} (mark-node-unreachable previous-graph graph graph' env)
                unreachable-attrs (filter #(set/subset? (all-attribute-resolvers env %) unreachable-syms) still-missing)]
            (update out' ::unreachable-attrs into unreachable-attrs)))))
    graph))

(defn runner-node-sym
  "Find the runner symbol for a resolver, on normal resolvers that is the resolver symbol,
  but for foreign resolvers it uses its ::pc/dynamic-sym."
  [{:com.wsscode.pathom.connect/keys [index-resolvers]}
   sym]
  (let [resolver (get index-resolvers sym)]
    (or (:com.wsscode.pathom.connect/dynamic-sym resolver)
        sym)))

(defn compute-resolver-graph
  [{::keys [unreachable-syms] :as graph}
   {::keys [run-next]
    :as    env}
   resolver]
  (let [resolver' (runner-node-sym env resolver)]
    (cond
      (contains? unreachable-syms resolver')
      graph

      :else
      (let [env  (assoc env pc-sym resolver' ::source-sym resolver)
            node (create-resolver-node graph env)]
        (-> graph
            (include-node env node)
            (cond-> (and run-next (not= run-next (::node-id node)))
                    (add-after-node run-next (::node-id node)))
            (compute-root-or env node))))))

(defn compute-input-resolvers-graph
  [graph
   {::keys [available-data]
    :as    env}
   inputs resolvers]
  (let [missing (into #{} (remove #(contains? available-data %)) inputs)
        env     (assoc env ::input (into {} (map #(hash-map % {})) inputs))]
    (if (contains? inputs (pc-attr env))
      graph
      (as-> graph <>
        (dissoc <> ::root)
        ; resolvers loop
        (reduce
          (fn [graph resolver] (compute-resolver-graph graph env resolver))
          <>
          resolvers)

        (if (::root <>)
          (-> <>
              (compute-missing-chain (assoc env ::previous-graph graph) missing)
              (compute-root-or env {::node-id (::root graph)}))
          (assoc <> ::root (::root graph)))))))

(defn prepare-ast
  "Prepare AST from query. This will lift placeholder nodes, convert
  query to AST and remove children keys that are already present in the current
  entity."
  [env ast]
  (let [entity (p/entity env)]
    (-> (p/lift-placeholders-ast env ast)
        (update :children
          (fn [children]
            (into []
                  (remove #(contains? entity (:key %)))
                  children))))))

(defn node-for-attribute-in-chain
  "Walks the graph run next chain until it finds the node that's providing the
  attribute."
  [graph
   {:com.wsscode.pathom.connect/keys [attribute] :as env}
   root]
  (loop [node-id root]
    (let [{::keys [run-next requires run-and] :as node} (get-node graph node-id)]
      (cond
        (and run-and (contains? requires attribute))
        (some
          #(node-for-attribute-in-chain graph env %)
          run-and)

        (contains? requires attribute)
        node-id

        run-next
        (recur run-next)))))

(defn update-node-attribute
  [graph {:com.wsscode.pathom.connect/keys [attribute] :as env}]
  (if-let [node-id (node-for-attribute-in-chain graph env (::root graph))]
    (-> (update graph ::index-attrs assoc attribute node-id)
        (update-in [::nodes node-id ::source-for-attrs] p.misc/sconj attribute))
    graph))

(defn find-first-ancestor
  "Traverse node after-node chain and returns the most distant ancestor node id."
  [graph node-id]
  (loop [node-id' node-id
         visited  #{}]
    (if (contains? visited node-id')
      (do
        (println "Ancestors Cycle detected" visited node-id')
        node-id)
      (let [{::keys [after-nodes]} (get-node graph node-id')
            next-id (first after-nodes)]
        (if (and (= 1 (count after-nodes))
                 (not (::run-and (get-node graph next-id))))
          (recur next-id (conj visited node-id'))
          node-id')))))

(defn push-root-to-ancestor [graph node-id]
  (set-root-node graph (find-first-ancestor graph node-id)))

(defn compute-attribute-graph*
  [{::keys [root index-attrs] :as graph}
   {:com.wsscode.pathom.connect/keys [index-oir attribute]
    :as                              env}]
  (cond
    (get index-attrs attribute)
    (if-let [node-id (get index-attrs attribute)]
      (-> (extend-node-run-next graph env node-id)
          (merge-node-requires node-id {(pc-attr env) {}})
          (push-root-to-ancestor node-id)
          (compute-root-and env {::node-id root}))
      graph)

    :else
    (let [graph'
          (as-> graph <>
            (dissoc <> ::root)
            (reduce-kv
              (fn [graph inputs resolvers]
                (compute-input-resolvers-graph graph env inputs resolvers))
              <>
              (get index-oir attribute))
            (update-node-attribute <> env))]
      (if (::root graph')
        (compute-root-and graph' env {::node-id root})
        (assoc graph' ::root root)))))

(defn compute-attribute-graph
  "Compute the run graph for a given attribute."
  [{::keys [unreachable-attrs] :as graph}
   {::keys                           [available-data attr-deps-trail]
    :com.wsscode.pathom.connect/keys [index-oir]
    {attr :key}                      :edn-query-language.ast/node
    :as                              env}]
  (let [env (assoc env pc-attr attr)]
    (cond
      (or (contains? available-data attr)
          (contains? unreachable-attrs attr)
          (contains? attr-deps-trail attr))
      graph

      (contains? index-oir attr)
      (compute-attribute-graph* graph env)

      :else
      (add-unreachable-attr graph attr))))

(defn compute-run-graph*
  "Generates a run plan for a given environment, the environment should contain the
  indexes in it (::pc/index-oir and ::pc/index-resolvers). It computes a plan to execute
  one level of an AST, the AST must be provided via the key :edn-query-language.ast/node.

       (compute-run-graph (assoc indexes :edn-query-language.ast/node ...))"
  ([graph env]
   (reduce
     (fn [graph ast]
       (compute-attribute-graph graph
         (assoc env :edn-query-language.ast/node ast)))
     graph
     (remove (comp eql/ident? :key) (:children (ast-node env)))))

  ([env]
   (compute-run-graph* (base-graph)
     (merge
       (base-env)
       env))))

(defn compute-run-graph
  "Generates a run plan for a given environment, the environment should contain the
  indexes in it (::pc/index-oir and ::pc/index-resolvers). It computes a plan to execute
  one level of an AST, the AST must be provided via the key :edn-query-language.ast/node.

       (compute-run-graph (assoc indexes :edn-query-language.ast/node ...))"
  ([graph env]
   (-> (compute-run-graph* (merge (base-graph) graph) (merge (base-env) env))))

  ([env]
   (compute-run-graph (base-graph)
     (merge
       (base-env)
       env))))