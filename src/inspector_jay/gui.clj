; Copyright (c) 2013-2014 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui
  "Defines Inspector Jay's graphical components"
  {:author "Tim Molderez"}
  (:use 
    [clojure.string :only [join]]
    [clojure.java.io :only [resource]]
    [clojure.java.javadoc]
    [seesaw
     [core :exclude [tree-options]]
     [color]
     [border]
     [font]]
    [inspector-jay
     [utils]
     [tree-node]
     [tree-model]])
  (:import
    [javax.swing Box JTextArea KeyStroke JFrame JPanel JTree JToolBar JComponent JToolBar$Separator UIManager JSplitPane]
    [javax.swing.tree DefaultTreeCellRenderer]
    [javax.swing.event TreeSelectionListener TreeExpansionListener TreeWillExpandListener]
    [javax.swing.tree ExpandVetoException]
    [java.awt Rectangle Toolkit]
    [java.awt.event KeyEvent ActionListener ActionEvent InputEvent]
    [java.lang.reflect Modifier]
    [net.java.balloontip BalloonTip CustomBalloonTip]
    [net.java.balloontip.positioners LeftAbovePositioner LeftBelowPositioner]
    [net.java.balloontip.styles IsometricBalloonStyle]))

(native!) ; Use the OS's native look and feel

(def gui-options ; Inspector Jay GUI options
  {:width 800
   :height 600
   :font (font :name :sans-serif :style #{:plain})
   :crumb-length 20
   :btip-style `(new IsometricBalloonStyle (UIManager/getColor "Panel.background") (color "#268bd2") 5)
   :btip-positioner `(new LeftAbovePositioner 8 5)})

(def tree-options ; Tree filtering options
  {:sorted true
   :methods true
   :fields true
   :public true
   :protected false
   :private false
   :static true
   :inherited false})

(defmulti to-string-breadcrumb
  "Retrieve a string to describe a tree node in a path of breadcrumbs"
  (fn [node] (-> node .getKind)))

(defmulti to-string
  "Retrieve a short description of a tree node"
  (fn [node] (-> node .getKind)))

(defmulti to-string-verbose
  "Retrieve a detailed description of a tree node"
  (fn [node] (-> node .getKind)))

(defmulti to-string-value
  "Retrieve a node's value as a string"
  (fn [node] (-> node .getCollectionKind)))

(defmulti get-icon
  "Retrieve the icon associated with a tree node"
  (fn [node] (-> node .getKind)))

(defmethod to-string-breadcrumb :default [node]
  (truncate 
    (-> node .getValue .toString)
    (gui-options :crumb-length)))
(defmethod to-string-breadcrumb :method [node]
  (truncate (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ")")
    (gui-options :crumb-length)))
(defmethod to-string-breadcrumb :field [node]
  (truncate (str (-> node .getField .getName)) 
    (gui-options :crumb-length)))

(defmethod to-string :default [node]
  (-> node .getValue .toString))
(defmethod to-string :method [node]
  (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ") : "
    (-> node .getMethod .getReturnType .getSimpleName)))
(defmethod to-string :field [node]
  (str
    (-> node .getField .getName)
    " : "
    (-> node .getValue)))

(defmethod to-string-verbose :default [node]
  (str
    (-> node .getValue .getClass)
    "\n\n"
    (to-string-value node)))
(defmethod to-string-verbose :method [node]
  (str
    (-> node .getMethod .toString)      
    (if (-> node .hasValue)
           (str 
             "\n\n" 
             (-> node .getValue .getClass .getName) " (dynamic type)"
             "\n\n" 
             (to-string-value node)))))
(defmethod to-string-verbose :field [node]
  (str 
    (-> node .getField)
    (if (-> node .hasValue)
      (str 
        "\n\n"
        (-> node .getValue .getClass .getName) " (dynamic type)"
        "\n\n"
        (to-string-value node)))))

(defmethod to-string-value :atom [node]
  (-> node .getValue .toString))
(defmethod to-string-value :sequence [node]
  (to-string-sequence (-> node .getValue)))
(defmethod to-string-value :collection [node]
  (to-string-sequence (seq(-> node .getValue))))  

(defmethod get-icon :default [node]
  (icon (resource "icons/genericvariable_obj.gif")))
(defmethod get-icon :method [node]
  (let
    [mod (-> node .getMethod .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/methpub_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/methpri_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/methpro_obj.gif"))
    :else (icon (resource "icons/methdef_obj.gif")))))
(defmethod get-icon :field [node]
  (let
    [mod (-> node .getField .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/field_public_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/field_private_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/field_protected_obj.gif"))
    :else (icon (resource "icons/field_default_obj.gif")))))

(defn tree-renderer ^DefaultTreeCellRenderer []
  "Returns a cell renderer which defines what each tree node should look like"
  (proxy [DefaultTreeCellRenderer] []
    (getTreeCellRendererComponent [tree value selected expanded leaf row hasFocus]
      (proxy-super getTreeCellRendererComponent tree value selected expanded leaf row hasFocus)
      (-> this (.setText (to-string value)))
      (-> this (.setIcon (get-icon value)))
      this)))

(defn tree-selection-listener ^TreeSelectionListener [info-panel crumbs-panel]  
  "Update the detailed information panel, as well as the breadcrumbs, whenever a tree node is selected"
  (proxy [TreeSelectionListener] []
    (valueChanged [event]
      (let [newPath (-> event .getNewLeadSelectionPath)]
        (if (not= newPath nil)
          (do 
            (config! info-panel :text (to-string-verbose (-> newPath .getLastPathComponent)))
            (config! crumbs-panel :text 
              (str
                "<html>"
                (join (interpose "<font color=\"#268bd2\"><b> &gt; </b></font>" 
                        (map to-string-breadcrumb (-> newPath .getPath))))
                "</html>"))))))))

(defn tree-expansion-listener ^TreeExpansionListener [info-panel]
  "Updates the detailed information panel whenever a node is expanded."
  (proxy [TreeExpansionListener] []
    (treeExpanded [event]
      (config! info-panel :text (to-string-verbose (-> event .getPath .getLastPathComponent))))
    (treeCollapsed [event])))

(defn tree-will-expand-listener ^TreeWillExpandListener []
  "Displays a dialog if the user needs to enter some actual parameters to invoke a method."
  (proxy [TreeWillExpandListener] []
    (treeWillExpand [event]
      (let [jtree (-> event .getSource)
            node (-> event .getPath .getLastPathComponent)]
        (if (not (-> node .isValueAvailable))
          (if (= 0 (count (-> node .getMethod .getParameterTypes)))
            ; No parameters needed; we can simply call .getValue to make the value available
            (-> node .getValue)
             ; Otherwise we'll need to ask the user to enter some parameter values
            (let [param-types (-> node .getMethod .getParameterTypes)
                  param-boxes (for [x param-types]
                                (text :tip (-> x .getSimpleName) :columns 32 ))
                  params (grid-panel :rows (inc (count param-types)) :columns 1)
                  ok-button (button :text "Call")
                  cancel-button (button :text "Cancel")
                  buttons (flow-panel)
                  border-panel (border-panel :center params :south buttons)]
              (-> buttons (.add ok-button))
              (-> buttons (.add cancel-button))
              (-> params (.add (label "Enter parameter values to call this method:")))
              (doseq [x param-boxes]
                (-> params (.add x)))
              (let [btip (new CustomBalloonTip 
                           jtree
                           border-panel
                           (-> jtree (.getPathBounds (-> event .getPath)))
                           (eval (gui-options :btip-style))
                           (eval (gui-options :btip-positioner))
                           nil)]
                (-> (first param-boxes) .requestFocus)
                (listen cancel-button :action (fn [e] 
                                                (-> btip .closeBalloon)
                                                (-> jtree .requestFocus)))
                (listen ok-button :action (fn [e]
                                            (let [args (for [x param-boxes]
                                                         (eval (read-string (-> x .getText))))]
                                              (-> node  (.invokeMethod args))
                                              ;(apply get-value-args node args)
                                              (-> btip .closeBalloon)
                                              (-> jtree (.expandPath (-> event .getPath)))
                                              (-> jtree .requestFocus))))) 
              
              (throw (new ExpandVetoException event))))))) ; Deny expanding the tree node; it will be expanded once the value is available
    (treeWillCollapse [event])))

(defn- open-javadoc [jtree]
  "Search Javadoc for the selected node (if present)"
  (let [selection (-> jtree .getLastSelectedPathComponent)]
    (if (not= selection nil)
      (javadoc (-> selection .getValueClass)))))

(defn- search-tree [^JTree tree ^String key start-row forward include-current]
  "Search a JTree for the first visible node whose value contains 'key', starting from the node at row 'start-row'.
   If 'forward' is true, we search forwards; otherwise we search backwards.
   If a matching node is found, its path is returned; otherwise nil is returned."
  (let [max-rows (-> tree .getRowCount)
        ukey (-> key .toUpperCase)
        increment (if forward 1 -1)
        start (if include-current start-row (mod (+ start-row increment max-rows) max-rows))]
    ; Keep looking through all rows until we either find a match, or we're back at the start
    (loop [row start]
      (let [path (-> tree (.getPathForRow row))
            node (to-string (-> path .getLastPathComponent))
            next-row (mod (+ row increment max-rows) max-rows)]
        (if (-> node .toUpperCase (.contains ukey))
          path
          (if (not= next-row start)
            (recur next-row)
            nil))))))

(defn- search-tree-and-select
  [^JTree tree text forward include-current]
  (let [start-row (if (-> tree .isSelectionEmpty) 0 (-> tree .getLeadSelectionRow))
        next-match (search-tree tree text start-row forward include-current)]
    (if (not= next-match nil)
      (doto tree
        (.setSelectionPath next-match)
        (.scrollPathToVisible next-match))
      (-> (Toolkit/getDefaultToolkit) .beep))))

(defn tool-panel ^JToolBar [^JFrame frame ^Object object ^JTree jtree ^JSplitPane split-pane]
  "Create the toolbar of the Inspector Jay window"
  (let [iconSize [24 :by 24]
        sort-button (toggle :icon (icon (resource "icons/alphab_sort_co.gif")) :size iconSize :selected? (tree-options :sorted))
        filter-button (button :icon (icon (resource "icons/filter_history.gif")) :size iconSize)
        filter-methods (checkbox :text "Methods" :selected? (tree-options :methods))
        filter-fields (checkbox :text "Fields" :selected? (tree-options :fields))
        filter-public (checkbox :text "Public" :selected? (tree-options :public))
        filter-protected (checkbox :text "Protected" :selected? (tree-options :protected))
        filter-private (checkbox :text "Private" :selected? (tree-options :private))
        filter-static (checkbox :text "Static" :selected? (tree-options :static))
        filter-inherited (checkbox :text "Inherited" :selected? (tree-options :inherited))
        update-filters (fn [e] 
                         (-> jtree (.setModel (tree-model object {:sorted (-> sort-button .isSelected)
                                                                  :methods (-> filter-methods .isSelected)
                                                                  :fields (-> filter-fields .isSelected)
                                                                  :public (-> filter-public .isSelected)
                                                                  :protected (-> filter-protected .isSelected)
                                                                  :private (-> filter-private .isSelected)
                                                                  :static (-> filter-static .isSelected)
                                                                  :inherited (-> filter-inherited .isSelected)})))
                         (-> jtree (.setSelectionPath (-> jtree (.getPathForRow 0)))))
        filter-panel (vertical-panel :items [filter-methods filter-fields 
                                             (separator) filter-public filter-protected filter-private
                                             (separator) filter-static filter-inherited])
        pane-button (toggle :icon (icon (resource "icons/details_view.gif")) :size iconSize :selected? true)
        doc-button (button :icon (icon (resource "icons/javadoc.gif")) :size iconSize)
        invoke-button (button :icon (icon (resource "icons/runlast_co.gif")) :size iconSize)
        filter-tip (delay (new BalloonTip 
                            filter-button
                            filter-panel
                            (eval (gui-options :btip-style))
                            (eval (gui-options :btip-positioner))
                            nil))
        search-txt (text :columns 20 :text "Search...")
        toolbar (toolbar :items [sort-button filter-button pane-button doc-button invoke-button 
                                 (Box/createHorizontalGlue) search-txt (Box/createHorizontalStrut 2)])]
    (-> toolbar (.setBorder (empty-border :thickness 1)))
    (-> search-txt (.setMaximumSize (-> search-txt .getPreferredSize)))
    
    (-> sort-button (.setFocusPainted false))
    (-> filter-button (.setFocusPainted false))
    (-> pane-button (.setFocusPainted false))
    (-> doc-button (.setFocusPainted false))
    (-> invoke-button (.setFocusPainted false))
    
    (-> sort-button (.setToolTipText "Sort alphabetically"))
    (-> filter-button (.setToolTipText "Filtering options..."))
    (-> pane-button (.setToolTipText "Toggle horizontal/vertical layout"))
    (-> doc-button (.setToolTipText "Search Javadoc (F1)"))
    (-> invoke-button (.setToolTipText "(Re)invoke selected method"))
    (-> search-txt (.setToolTipText "Search visible tree nodes"))
    
    ; Sort button
    (listen sort-button :action update-filters)
    ; Open/close filter options menu
    (listen filter-button :action (fn [e]
                                    (if (not (realized? filter-tip))
                                      ; If opened for the first time, add a listener that hides the menu on mouse exit
                                      (listen @filter-tip :mouse-exited (fn [e]
                                                                          (if (not (-> @filter-tip (.contains (-> e .getPoint))))
                                                                            (-> @filter-tip (.setVisible false)))))
                                      (if (-> @filter-tip .isVisible)
                                        (-> @filter-tip (.setVisible false))
                                        (-> @filter-tip (.setVisible true))))))
    ; Filter checkboxes
    (listen filter-methods :action update-filters)
    (listen filter-fields :action update-filters)
    (listen filter-public :action update-filters)
    (listen filter-protected :action update-filters)
    (listen filter-private :action update-filters)
    (listen filter-static :action update-filters)
    (listen filter-inherited :action update-filters)
    ; Toggle horizontal/vertical layout
    (listen pane-button :action (fn [e]
                                  (if (-> pane-button .isSelected)
                                    (-> split-pane (.setOrientation 0))
                                    (-> split-pane (.setOrientation 1)))))
    ; Open javadoc of selected tree node
    (listen doc-button :action (fn [e] (open-javadoc jtree)))
    ; Clear search field initially
    (listen search-txt :focus-gained (fn [e] (-> search-txt (.setText ""))
                                       (-> search-txt (.removeFocusListener (last(-> search-txt (.getFocusListeners)))))))
    ; When typing in the search field, look for matches
    (listen search-txt #{:remove-update :insert-update} (fn [e] (search-tree-and-select jtree (-> search-txt .getText) true true)))
    ; Add key bindings
    (let [f3-key (KeyStroke/getKeyStroke KeyEvent/VK_F3 0)
          shift-f3-key (KeyStroke/getKeyStroke KeyEvent/VK_F3 InputEvent/SHIFT_DOWN_MASK)
          ctrl-f-key (KeyStroke/getKeyStroke KeyEvent/VK_F (-> (Toolkit/getDefaultToolkit) .getMenuShortcutKeyMask))]
      ; Find next
      (-> frame .getRootPane (.registerKeyboardAction
                               (proxy [ActionListener] []
                                 (actionPerformed [e]
                                   (search-tree-and-select jtree (-> search-txt .getText) true false)))
                               f3-key JComponent/WHEN_IN_FOCUSED_WINDOW))
      ; Find previous
      (-> frame .getRootPane (.registerKeyboardAction
                               (proxy [ActionListener] []
                                 (actionPerformed [e]
                                   (search-tree-and-select jtree (-> search-txt .getText) false false)))
                               shift-f3-key JComponent/WHEN_IN_FOCUSED_WINDOW))
      ; Go to search field (or back to the tree)
      (-> frame .getRootPane (.registerKeyboardAction
                               (proxy [ActionListener] []
                                 (actionPerformed [e]
                                   (if (-> search-txt .hasFocus)
                                     (-> jtree .requestFocus)
                                     (-> search-txt .requestFocus))))
                               ctrl-f-key JComponent/WHEN_IN_FOCUSED_WINDOW)))
    toolbar))

(defn bind-keys
  [^JFrame frame ^JTree tree]
  "Attach various key bindings to the Inspector Jay window"
  (let
    [f1-key (KeyStroke/getKeyStroke KeyEvent/VK_F1 0)
     esc-key (KeyStroke/getKeyStroke KeyEvent/VK_ESCAPE 0)]
    ; Search javadoc for the currently selected node and open it in a browser window
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (open-javadoc tree)))
                                 f1-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Close the window
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (-> frame .dispose)))
                                 esc-key JComponent/WHEN_IN_FOCUSED_WINDOW))))

(defn main-gui ^JFrame [^Object object]
  "Create and show an Inspector Jay window to inspect a given objects"
  (let [f (frame :title (str "Object inspector : " (.toString object)) 
            :size [(gui-options :width) :by (gui-options :height)]
            :on-close :dispose)
        obj-info (text :multi-line? true :editable? false :font (gui-options :font))
        obj-tree (tree :model (tree-model object tree-options))
        crumbs (label :icon (icon (resource "icons/toggle_breadcrumb.gif")))
        obj-info-scroll (scrollable obj-info)
        obj-tree-scroll (scrollable obj-tree)
        split-pane (top-bottom-split obj-info-scroll obj-tree-scroll :divider-location 1/5)
        toolbar (tool-panel f object obj-tree split-pane)
        main-panel (border-panel :north toolbar :south crumbs :center split-pane)]
    (-> split-pane (.setDividerSize 9))
    (-> obj-info-scroll (.setBorder (empty-border)))
    (-> obj-tree-scroll (.setBorder (empty-border)))
    (doto obj-tree
      (.setCellRenderer (tree-renderer))
      (.addTreeSelectionListener (tree-selection-listener obj-info crumbs))
      (.addTreeExpansionListener (tree-expansion-listener obj-info))
      (.addTreeWillExpandListener (tree-will-expand-listener))
      (.setSelectionPath (-> obj-tree (.getPathForRow 0))))
    (bind-keys f obj-tree)
    (config! f :content main-panel)
    (-> f show!)
    (-> obj-tree .requestFocus)
    f))