; Copyright (c) 2013-2014 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.model.tree-node
  "Defines the data structure of a tree node"
  {:author "Tim Molderez"}
  (:import
     [java.lang.reflect Modifier Method Field InvocationTargetException]
     [clojure.lang Delay])
  (:use 
    [clojure.string :only [join]]))

(declare ^:dynamic meth-args) ; This declaration is needed so we can make method arguments available inside the delay-function that invokes a method; see method-node..

(defn invoke-method [method object & args]
  "Call a method on an object via reflection, and return its return value.
   If the call produces an exception, it is caught and returned as the return value instead."
  (try
    (-> method (.invoke object (to-array args)))
    (catch InvocationTargetException e (-> e .getCause))))

(def get-visible-fields 
  (memoize (fn [cls opts]
  "Retrieve all fields that are visible to any instances of class cls.
   More specifically, all fields declared directly in cls, and all public/protected fields found in its ancestor classes."
  (let
    ; All fields declared directly in cls
    [declFields (filter (fn [aField]
                                      (and
                                        (if (opts :static) true (not (Modifier/isStatic (-> aField .getModifiers))))
                                        (if (opts :private) true (not (Modifier/isPrivate (-> aField .getModifiers))))
                                        (if (opts :protected) true (not (Modifier/isProtected (-> aField .getModifiers))))
                                        (if (opts :public) true (not (Modifier/isPublic (-> aField .getModifiers))))))
                              (-> cls .getDeclaredFields))
     ; All fields declared in the ancestor classes of cls
     ancestorFields (if (or (= java.lang.Object cls) (not (opts :inherited)))
                      []
                      (get-visible-fields (-> cls .getSuperclass) opts))
     ; Remove all private and hidden fields from ancestorFields
     filteredAncestors (filter (fn [aField]
                                 (and
                                   (not (Modifier/isPrivate (-> aField .getModifiers)))
                                   (every? (fn [dField] (not= (-> dField .getName) (-> aField .getName)))
                                         declFields)))
                               ancestorFields)]
    (concat
      (if (opts :sorted)
        (sort-by (memfn getName) declFields)
        declFields)
      filteredAncestors)))))

(def get-visible-methods 
  (memoize (fn [cls opts]
             "Retrieve all methods that are visible to any instances of class cls.
   More specifically, all methods declared directly in cls, and all public/protected methods found in its ancestor classes."
             (let
               ; All methods declared directly in cls
               [declMethods (filter (fn [aMethod]
                                      (and
                                        (if (opts :static) true (not (Modifier/isStatic (-> aMethod .getModifiers))))
                                        (if (opts :private) true (not (Modifier/isPrivate (-> aMethod .getModifiers))))
                                        (if (opts :protected) true (not (Modifier/isProtected (-> aMethod .getModifiers))))
                                        (if (opts :public) true (not (Modifier/isPublic (-> aMethod .getModifiers))))))
                              (-> cls .getDeclaredMethods))
                ; All methods declared in the ancestor classes of cls
                ancestorMethods (if (or (= java.lang.Object cls) (not (opts :inherited)))
                                  []
                                  (get-visible-methods (-> cls .getSuperclass) opts))
                ; Remove all private and hidden methods from ancestorMethods
                filteredAncestors (filter (fn [aMethod]
                                            (and
                                              (not (Modifier/isPrivate (-> aMethod .getModifiers)))
                                              (every? (fn [dMethod]
                                                        (and
                                                          (not= (-> dMethod .getName) (-> aMethod .getName))
                                                          (not= (-> dMethod .getParameterTypes) (-> aMethod .getParameterTypes))))
                                                declMethods)))
                                    ancestorMethods)]
               (concat
                 (if (opts :sorted)
                   (sort-by (memfn getName) declMethods)
                   declMethods)
                 filteredAncestors)))))

(defprotocol ITreeNode
  (getValue [this]
    "Retrieve the Java object contained by this node. (may be nil) In case the object is not available yet, it will be made available now. 
     (This typically is the case if the object is the return value of a method that has not been invoked yet.)")
  (hasValue [this]
    "Is the object in this node available, and does it have a non-nil value?")
  (mightHaveValue [this]
    "Might this node contain a non-nil object?
     (If the object is not available yet, we assume it might have a non-nil value..)")
  (isValueAvailable [this]
    "Is the object contained by this node available?")
  (getValueClass [this]
    "Retrieve the type of the object contained by this node.")
  (getMethod [this]
    "Retrieve the method that produces the node's value as its return value. (may be nil)")
  (invokeMethod [this args]
    "Retrieve this node's value by invoking its method")
  (getField [this]
    "Retrieve the field associated with this node's value. (may be nil)")
  (getMethods [this opts]
    "Retrieve the methods in the node value's class. (if this value is nil, nil is returned)")
  (getFields [this opts]
    "Retrieve the fields in the node value's class. (if this value is nil, nil is returned)")
  (getKind [this]
    "Retrieve a keyword that represents this node:
     :object   This is a generic object node, not associated with a method or a field.
     :method   The object contained in this node is the return value of a method call.
     :field    The object contained in this node is the value of a field.")
  (getCollectionKind [this]
    "Determine whether the object in this node represents some kind of collection:
     :atom        The object is not a collection.
     :sequence    The object is can be sequenced. (supports the nth and count functions)
     :collection  The object is any other kind of collection, e.g. a set or map. (supports the seq and count functions)"))

(deftype TreeNode [data]
  ITreeNode
 (getValue [this] 
   (force (data :value)))
 (hasValue [this]
   (if (instance? Delay (data :value))
     (and 
       (realized? (data :value)) 
       (not= (deref (data :value)) nil))
     (not= (data :value) nil)))
 (mightHaveValue [this]
    (and
      (contains? data :value))
      (not= (data :value) nil))
 (isValueAvailable [this]
   (if (instance? Delay (data :value))
     (realized? (data :value))
     true))
 (getValueClass [this]
   (case (-> this .getKind)
     :object (-> (data :value) .getClass)
     :method (-> (data :method) .getReturnType)
     :field (-> (data :field) .getType)))
 (getMethod [this]
   (data :method))
 (invokeMethod [this args]
   (binding [meth-args args] ; Makes the method arguments available to the value's delay-function
  (-> this .getValue)))
 (getField [this]
   (data :field))
 (getMethods [this opts]
   (if (not= (-> this .getValue) nil)
     (get-visible-methods (-> this .getValue .getClass) (dissoc opts :methods :fields))
     nil))
 (getFields [this opts]
   (if (not= (-> this .getValue) nil)
     (get-visible-fields (-> this .getValue .getClass) (dissoc opts :methods :fields))
     nil))
 (getKind [this] 
   (cond
     (contains? data :method) :method
      (contains? data :field) :field
     :else :object))
 (getCollectionKind [this]
   (let [cls (-> this .getValueClass)]
    (cond
      ; A :sequence is anything that supports the nth function..
    (-> clojure.lang.Sequential (.isAssignableFrom cls)) :sequence
      (-> java.util.RandomAccess (.isAssignableFrom cls)) :sequence
      (-> cls .isArray) :sequence
      ; All collections support the count and seq functions.. 
    (-> java.util.Collection (.isAssignableFrom cls)) :collection
      (-> java.util.Map (.isAssignableFrom cls)) :collection
      :else :atom))))

(defn object-node ^TreeNode [^Object object]
  "Create a new generic object node."
  (new TreeNode {:value object}))

(defn method-node ^TreeNode [^Method method ^Object receiver]
  "Create a method node, given a method and a receiver object.
   The object contained by this node is the return value of invoking the method."
  (let []
    (-> method (.setAccessible true)) ; Enable access to private methods
    (new TreeNode {:method method :value (delay
                                           (if (sequential? meth-args)
                                             (apply invoke-method method receiver meth-args)
                                             (invoke-method method receiver)))})))

(defn reset-method-node [^TreeNode node ^Object receiver]
  "Clear the return value of a method node, and set a new receiver object."
  (let []
    )) 

(defn field-node ^TreeNode [^Field field ^Object receiver]
  "Create a field node, given a field and a receiver object.
   The object contained by this node is the field's value."
  (-> field (.setAccessible true)) ; Enable access to private fields
 (new TreeNode {:field field :value (-> field (.get receiver))}))