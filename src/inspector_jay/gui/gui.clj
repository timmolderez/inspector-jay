; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.gui
  "Defines Inspector Jay's graphical user interface"
  {:author "Tim Molderez"}
  (:require
    [clojure.string :as s]
    [clojure.java
     [io :as io]
     [javadoc :as jdoc]]
    [seesaw
     [core :as seesaw]
     [color :as color]
     [border :as border]
     [font :as font]]
    [inspector-jay.gui
     [utils :as utils]
     [node-properties :as nprops]]
    [inspector-jay.model
     [tree-node :as node]
     [tree-model :as model]])
  (:import
    [javax.swing Box JTextArea KeyStroke JFrame JPanel JTree JToolBar JComponent JToolBar$Separator UIManager JSplitPane JTabbedPane]
    [javax.swing.tree DefaultTreeCellRenderer]
    [javax.swing.event TreeSelectionListener TreeExpansionListener TreeWillExpandListener]
    [javax.swing.tree ExpandVetoException]
    [java.awt Rectangle Toolkit]
    [java.awt.event KeyEvent ActionListener ActionEvent InputEvent WindowEvent]
    [net.java.balloontip BalloonTip CustomBalloonTip]
    [net.java.balloontip.positioners LeftAbovePositioner LeftBelowPositioner]
    [net.java.balloontip.styles IsometricBalloonStyle]
    [net.java.balloontip.utils TimingUtils]
    (inspector_jay.model.tree_node TreeNode)))

(seesaw/native!) ; Use the OS's native look and feel

(def
  ^{:doc "When a new inspector is opened, these are the default options.
          You can customize these options via keyword arguments, 
          e.g. (inspect an-object :sorted false :pause true)"}
  default-options
  {; Tree filtering options
   :sorted true ; Alphabetically sort members
   :methods true ; Show/hide methods
   :fields true
   :public true 
   :protected false
   :private false
   :static true
   :inherited true ; Show/hide inherited members
   
   ; Miscellaneous options
   :window-name nil ; If given a name, the inspector is opened as a new tab in the window with that name.
                    ; If that window does not exist yet, it will be created. This is useful if you want to
                    ; divide your inspectors into groups. 
   :new-window false ; If true, the inspector is opened in a new window.
                     ; Otherwise, the inspector is opened as a new tab.
                     ; This option is ignored if :window-name is used.
   :pause false ; If true, the current thread will be paused when opening an inspector.
                ; Execution will resume either when clicking the resume button, or closing the inspector tab/window.
   :vars nil ; You can use this to pass in any extra information to an inspector window.
             ; This is useful when invoking a method in the inspector, and you need to fill in some argument values.
             ; The value of :vars will then be available in the inspector under the variable named vars.
   })

(def
  ^{:doc "Inspectory Jay GUI options"}
  gui-options
  {:width 1024
   :height 768
   :font (font/font :name :sans-serif :style #{:plain})
   :crumb-length 32
   :max-tabs 64
   :btip-style `(new IsometricBalloonStyle (UIManager/getColor "Panel.background") (color/color "#268bd2") 5)
   :btip-error-style `(new IsometricBalloonStyle (UIManager/getColor "Panel.background") (color/color "#d32e27") 3)
   :btip-positioner `(new LeftAbovePositioner 8 5)})

(declare inspector-window) ; Forward declaration

; Global variable! List of all open Inspector Jay windows (Each element is a map with keys :window and :name)
(def jay-windows (atom []))

; Global variable! Keeps track of the last selected tree node. (across all tabs/windows)
(def last-selected-node (atom nil))

(defn- error
  "Show an error message near a given component"
  [^String message ^JComponent component]
  (TimingUtils/showTimedBalloon (new BalloonTip 
                                     component
                                     (seesaw/label message)
                                     (eval (gui-options :btip-error-style))
                                     (eval (gui-options :btip-positioner))
                                     nil)
                                3000)
  (-> (Toolkit/getDefaultToolkit) .beep))

(defn- tree-renderer
  "Returns a cell renderer which defines what each tree node should look like"
  ^DefaultTreeCellRenderer []
  (proxy [DefaultTreeCellRenderer] []
    (getTreeCellRendererComponent [tree value selected expanded leaf row hasFocus]
      (proxy-super getTreeCellRendererComponent tree value selected expanded leaf row hasFocus)
      (-> this (.setText (nprops/to-string value)))
      (-> this (.setIcon (nprops/get-icon value)))
      this)))

(defn last-selected-value 
  "Retrieve the value of the tree node that was last selected.
   (An exception is thrown if we can't automatically obtain this value .. e.g. in case the
   value is not available yet and the last selected node is a method with parameters.)"
  []
  (.getValue @last-selected-node))

(defn- tree-selection-listener 
  "Whenever a tree node is selected, update the detailed information panel, the breadcrumbs, 
   and the last-selected-value variable."
  ^TreeSelectionListener [info-panel crumbs-panel]  
  (proxy [TreeSelectionListener] []
    (valueChanged [event]
      (let [new-path (-> event .getNewLeadSelectionPath)]
        (if (not= new-path nil)
          (let [new-node (-> new-path .getLastPathComponent)]
            (swap! last-selected-node (fn [x] new-node))
            (seesaw/config! info-panel :text (nprops/to-string-verbose new-node))
            (seesaw/config! crumbs-panel :text 
                            (str
                              "<html>"
                              (s/join (interpose "<font color=\"#268bd2\"><b> &gt; </b></font>" 
                                                 (map (fn [x] (nprops/to-string-breadcrumb x (:crumb-length gui-options))) 
                                                      (-> new-path .getPath))))
                              "</html>"))))))))

(defn- tree-expansion-listener
  "Updates the detailed information panel whenever a node is expanded."
  ^TreeExpansionListener [info-panel]
  (proxy [TreeExpansionListener] []
    (treeExpanded [event]
      (seesaw/config! info-panel :text (nprops/to-string-verbose (-> event .getPath .getLastPathComponent))))
    (treeCollapsed [event])))

(defn- tree-will-expand-listener 
  "Displays a dialog if the user needs to enter some actual parameters to invoke a method."
  ^TreeWillExpandListener [shared-vars]
  (proxy [TreeWillExpandListener] []
    (treeWillExpand [event]
      (let [jtree (-> event .getSource)
            node (-> event .getPath .getLastPathComponent)]
        (if (not (-> node .isValueAvailable))
          (if (= 0 (count (-> node .getMethod .getParameterTypes)))
            ; No parameters needed; we can simply call .getValue to make the value available
            (-> node .getValue)
            ; Otherwise we'll need to ask the user to enter some parameter values
            (let [raw-types (-> node .getMethod .getParameterTypes)
                  ; Swap Java's primitive types for their corresponding wrappers
                  param-types (for [x raw-types]
                                (let [type (-> x .toString)]
                                  (cond
                                    (= type "byte") java.lang.Byte
                                    (= type "short") java.lang.Short
                                    (= type "int") java.lang.Integer
                                    (= type "long") java.lang.Long
                                    (= type "float") java.lang.Float
                                    (= type "double") java.lang.Double
                                    (= type "boolean") java.lang.Boolean
                                    (= type "char") java.lang.Character
                                    :else x)))
                  param-boxes (for [x param-types]
                                (seesaw/text :tip (-> x .getSimpleName) :columns 32 ))
                  params (seesaw/grid-panel :rows (inc (count param-types)) :columns 1)
                  ok-button (seesaw/button :text "Call")
                  cancel-button (seesaw/button :text "Cancel")
                  buttons (seesaw/flow-panel)
                  border-panel (seesaw/border-panel :center params :south buttons)]
              (-> buttons (.add ok-button))
              (-> buttons (.add cancel-button))
              (-> params (.add (seesaw/label "Enter parameter values to call this method:")))
              (doseq [x param-boxes]
                (-> params (.add x)))
              (let [; Form to enter paramter values
                    btip (new CustomBalloonTip 
                              jtree
                              border-panel
                              (-> jtree (.getPathBounds (-> event .getPath)))
                              (eval (gui-options :btip-style))
                              (eval (gui-options :btip-positioner))
                              nil)
                    ; Button to submit the form, and invoke the requested method
                    ok-handler (fn [e]
                                 (try (let [args (for [i (range 0 (count param-boxes))]
                                                   ; Try to evaluate the expression to obtain the current parameter's value
                                                   (try
                                                     (let [value (node/eval-arg (-> (nth param-boxes i) .getText) shared-vars)
                                                           type (nth param-types i)]
                                                       (cond
                                                         ; Do primitive type conversion when necessary
                                                         (= type java.lang.Short) (short value) ; Convert long to short
                                                         (= type java.lang.Float) (float value) ; Convert double to float
                                                         (= type java.lang.Integer) (int value) ; Convert long to int
                                                         :else value))
                                                     (catch Exception e 
                                                       (do
                                                         (error (-> e .getMessage) (nth param-boxes i))
                                                         (throw (Exception.))))))]
                                        (doseq [x args] x) ; If something went wrong with the arguments, this should trigger the exception before attempting an invocation..
                                        (-> node (.invokeMethod args))
                                        (-> btip .closeBalloon)
                                        (-> jtree (.expandPath (-> event .getPath)))
                                        (-> jtree (.setSelectionPath (-> event .getPath))) 
                                        (-> jtree .requestFocus))
                                   (catch Exception e )))
                    cancel-handler (fn [e]
                                     (-> btip .closeBalloon)
                                     (-> jtree .requestFocus))
                    key-handler (fn [e]
                                  (cond 
                                    (= (-> e .getKeyCode) KeyEvent/VK_ENTER) (ok-handler e)
                                    (= (-> e .getKeyCode) KeyEvent/VK_ESCAPE) (cancel-handler e)))]
                (-> (first param-boxes) .requestFocus)
                (doseq [x param-boxes]
                  (seesaw/listen x :key-pressed key-handler))
                (seesaw/listen cancel-button :action (fn [e] 
                                                       (-> btip .closeBalloon)
                                                       (-> jtree .requestFocus)))
                (seesaw/listen ok-button :action ok-handler))
              (throw (new ExpandVetoException event))))))) ; Deny expanding the tree node; it will only be expanded once the value is available
    (treeWillCollapse [event])))

(defn- open-javadoc
  "Search Javadoc for the selected node (if present)"
  [jtree]
  (let [selection (-> jtree .getLastSelectedPathComponent)]
    (if (not= selection nil)
      (jdoc/javadoc (nprops/get-javadoc-class selection)))))

(defn- inspect-node
  "Open the currently selected node in a new inspector"
  [jtree inspect-button]
  (let [selection (-> jtree .getLastSelectedPathComponent)]
    (if (-> selection .hasValue)
      (let
        [window (seesaw/to-root jtree)
         name (some (fn [w] (when (= (:window w) window) (:name w))) @jay-windows)]
        (inspector-window (-> selection .getValue) :window-name name)) 
      (if (= (-> selection .getKind) :method)
        (error "Can't inspect the return value of this method. (Have you already called it?)" inspect-button)
        (error "Can't inspect this node; it doesn't have a value." inspect-button))))
  )

(defn- reinvoke
  "(Re)invoke the selected method node"
  [jtree]
  (let [selection (-> jtree .getLastSelectedPathComponent)
        selection-path (-> jtree .getSelectionPath)
        selection-row (-> jtree  (.getRowForPath selection-path))]
    (if (= (-> selection .getKind) :method)
      (do 
        (-> jtree (.collapsePath selection-path))
        (-> jtree .getModel (.valueForPathChanged selection-path (node/object-node (new Object))))
        (-> jtree (.expandRow selection-row))
        (-> jtree (.setSelectionRow selection-row))))))

(defn- resume-execution 
  "Wake up any threads waiting for a lock object"
  [lock]
  (locking lock (.notify lock)))

(defn- search-tree
  "Search a JTree for the first visible node whose value contains 'key', starting from the node at row 'start-row'.
   If 'forward' is true, we search going forward; otherwise backwards.
   If a matching node is found, its path is returned; otherwise nil is returned."
  [^JTree tree ^String key start-row forward include-current]
  (let [max-rows (-> tree .getRowCount)
        ukey (-> key .toUpperCase)
        increment (if forward 1 -1)
        start (if include-current start-row (mod (+ start-row increment max-rows) max-rows))]
    ; Keep looking through all rows until we either find a match, or we're back at the start
    (loop [row start]
      (let [path (-> tree (.getPathForRow row))
            node (nprops/to-string (-> path .getLastPathComponent))
            next-row (mod (+ row increment max-rows) max-rows)]
        (if (-> node .toUpperCase (.contains ukey))
          path
          (if (not= next-row start)
            (recur next-row)
            nil))))))

(defn- search-tree-and-select
  "Search the inspector tree and select the node that was found, if any"
  [^JTree tree text forward include-current]
  (let [start-row (if (-> tree .isSelectionEmpty) 0 (-> tree .getLeadSelectionRow))
        next-match (search-tree tree text start-row forward include-current)]
    (if (not= next-match nil)
      (doto tree
        (.setSelectionPath next-match)
        (.scrollPathToVisible next-match))
      (-> (Toolkit/getDefaultToolkit) .beep))))

(defn- tool-panel 
  "Create the toolbar of the Inspector Jay window"
  ^JToolBar [^Object object ^JTree jtree ^JSplitPane split-pane tree-options]
  (let [iconSize [24 :by 24]
        sort-button (seesaw/toggle :icon (seesaw/icon (io/resource "icons/alphab_sort_co.gif")) :size iconSize :selected? (tree-options :sorted))
        filter-button (seesaw/button :icon (seesaw/icon (io/resource "icons/filter_history.gif")) :size iconSize)
        filter-methods (seesaw/checkbox :text "Methods" :selected? (tree-options :methods))
        filter-fields (seesaw/checkbox :text "Fields" :selected? (tree-options :fields))
        filter-public (seesaw/checkbox :text "Public" :selected? (tree-options :public))
        filter-protected (seesaw/checkbox :text "Protected" :selected? (tree-options :protected))
        filter-private (seesaw/checkbox :text "Private" :selected? (tree-options :private))
        filter-static (seesaw/checkbox :text "Static" :selected? (tree-options :static))
        filter-inherited (seesaw/checkbox :text "Inherited" :selected? (tree-options :inherited))
        ; Function to refresh the inspector tree given the current options in the filter menu
        update-filters (fn [e]
                         (-> jtree (.setModel (model/tree-model object {:sorted (-> sort-button .isSelected)
                                                                        :methods (-> filter-methods .isSelected)
                                                                        :fields (-> filter-fields .isSelected)
                                                                        :public (-> filter-public .isSelected)
                                                                        :protected (-> filter-protected .isSelected)
                                                                        :private (-> filter-private .isSelected)
                                                                        :static (-> filter-static .isSelected)
                                                                        :inherited (-> filter-inherited .isSelected)})))
                         (-> jtree (.setSelectionPath (-> jtree (.getPathForRow 0)))))
        filter-panel (seesaw/vertical-panel :items [filter-methods filter-fields 
                                                    (seesaw/separator) filter-public filter-protected filter-private
                                                    (seesaw/separator) filter-static filter-inherited])
        pane-button (seesaw/toggle :icon (seesaw/icon (io/resource "icons/details_view.gif")) :size iconSize :selected? true)
        inspect-button (seesaw/button :icon (seesaw/icon (io/resource "icons/insp_sbook.gif")) :size iconSize)
        doc-button (seesaw/button :icon (seesaw/icon (io/resource "icons/javadoc.gif")) :size iconSize)
        invoke-button (seesaw/button :icon (seesaw/icon (io/resource "icons/runlast_co.gif")) :size iconSize)
        refresh-button (seesaw/button :icon (seesaw/icon (io/resource "icons/nav_refresh.gif")) :size iconSize)
        filter-tip (delay (new BalloonTip ; Only create the filter menu once needed
                               filter-button
                               filter-panel
                               (eval (gui-options :btip-style))
                               (eval (gui-options :btip-positioner))
                               nil))
        resume-button (if (:pause tree-options) 
                        (seesaw/button :icon (seesaw/icon (io/resource "icons/resume_co.gif")) :size iconSize))
        search-txt (seesaw/text :columns 20 :text "Search...")
        toolbar-items (remove nil? [sort-button filter-button pane-button inspect-button doc-button invoke-button refresh-button resume-button
                                    (Box/createHorizontalGlue) search-txt (Box/createHorizontalStrut 2)])
        toolbar (seesaw/toolbar :items toolbar-items)]
    (doto toolbar
      (.setBorder (border/empty-border :thickness 1))
      (.setFloatable false))
    (-> search-txt (.setMaximumSize (-> search-txt .getPreferredSize)))
    ; Ditch the redundant dotted rectangle when a button is focused 
    (-> sort-button (.setFocusPainted false))
    (-> filter-button (.setFocusPainted false))
    (-> pane-button (.setFocusPainted false))
    (-> inspect-button (.setFocusPainted false))
    (-> doc-button (.setFocusPainted false))
    (-> invoke-button (.setFocusPainted false))
    (-> refresh-button (.setFocusPainted false))
    ; Set tooltips
    (-> sort-button (.setToolTipText "Sort alphabetically"))
    (-> filter-button (.setToolTipText "Filtering options..."))
    (-> pane-button (.setToolTipText "Toggle horizontal/vertical layout"))
    (-> inspect-button (.setToolTipText "Open selected node in new inspector"))
    (-> doc-button (.setToolTipText "Search Javadoc (F1)"))
    (-> invoke-button (.setToolTipText "(Re)invoke selected method (F4)"))
    (-> refresh-button (.setToolTipText "Refresh tree"))
    (-> search-txt (.setToolTipText "Search visible tree nodes (F3 / Shift-F3)"))
    ; Resume button
    (if (:pause tree-options)
      (do
        (-> resume-button (.setFocusPainted false))
        (-> resume-button (.setToolTipText "Resume execution"))
        (seesaw/listen resume-button :action (fn [e]
                                               (resume-execution (.getParent toolbar))
                                               (-> resume-button (.setEnabled false))
                                               (-> resume-button (.setToolTipText "Resume execution (already resumed)"))))))
    ; Sort button
    (seesaw/listen sort-button :action update-filters)
    ; Open/close filter options menu
    (seesaw/listen filter-button :action (fn [e]
                                           (if (not (realized? filter-tip))
                                             ; If opened for the first time, add a listener that hides the menu on mouse exit
                                             (seesaw/listen @filter-tip :mouse-exited (fn [e]
                                                                                        (if (not (-> @filter-tip (.contains (-> e .getPoint))))
                                                                                          (-> @filter-tip (.setVisible false)))))
                                             (if (-> @filter-tip .isVisible)
                                               (-> @filter-tip (.setVisible false))
                                               (-> @filter-tip (.setVisible true))))))
    ; Filter checkboxes
    (seesaw/listen filter-methods :action update-filters)
    (seesaw/listen filter-fields :action update-filters)
    (seesaw/listen filter-public :action update-filters)
    (seesaw/listen filter-protected :action update-filters)
    (seesaw/listen filter-private :action update-filters)
    (seesaw/listen filter-static :action update-filters)
    (seesaw/listen filter-inherited :action update-filters)
    ; Toggle horizontal/vertical layout
    (seesaw/listen pane-button :action (fn [e]
                                         (if (-> pane-button .isSelected)
                                           (-> split-pane (.setOrientation 0))
                                           (-> split-pane (.setOrientation 1)))))
    ; Open selected node in new inspector
    (seesaw/listen inspect-button :action (fn [e] (inspect-node jtree inspect-button)))
    ; Open javadoc of selected tree node
    (seesaw/listen doc-button :action (fn [e] (open-javadoc jtree)))
    ; (Re)invoke the selected method
    (seesaw/listen invoke-button :action (fn [e] (reinvoke jtree)))
    ; Refresh the tree
    (seesaw/listen refresh-button :action update-filters)
    ; Clear search field initially
    (seesaw/listen search-txt :focus-gained (fn [e] 
                                              (-> search-txt (.setText ""))
                                              (-> search-txt (.removeFocusListener (last(-> search-txt (.getFocusListeners)))))))
    ; When typing in the search field, look for matches
    (seesaw/listen search-txt #{:remove-update :insert-update} (fn [e]
                                                                 (search-tree-and-select jtree (-> search-txt .getText) true true)))
    toolbar))

(defn- get-jtree
  "Retrieve the object tree from an inspector Jay panel"
  [^JPanel panel]
  (let [split-pane (nth (-> panel .getComponents) 2)
        scroll-pane (nth (-> split-pane .getComponents) 2)
        viewport (nth (-> scroll-pane .getComponents) 0)
        jtree (-> viewport .getView)]
    jtree))

(defn- get-search-field 
  "Retrieve the search field from an inspector Jay panel"
  [^JPanel panel]
  (let [toolbar (nth (-> panel .getComponents) 0)
        idx (- (count (-> toolbar .getComponents)) 2) ; It's the second to last component in the toolbar..
        search-field (nth (-> toolbar .getComponents) idx)]
    search-field))

(defn- get-selected-tab
  "Retrieve the currently selected tab of an inspector Jay window"
  ^JPanel [^JFrame window]
  (let [content (-> window .getContentPane)]
    (if (instance? JTabbedPane content)
      (-> content .getSelectedComponent)
      content)))

(defn- close-tab
  "Close the currently selected tab in an Inspector Jay window"
  [window tab]
  (let [content (-> window .getContentPane)
        isTabbed (instance? JTabbedPane content)] 
    (if (not isTabbed)
      ; Close the window if there only is one object opened
      (-> window (.dispatchEvent (new WindowEvent window WindowEvent/WINDOW_CLOSING)))
      ; Close the tab
      (do
        (resume-execution (-> content (.getComponentAt (-> content .getSelectedIndex))))
        (-> content (.removeTabAt (-> content .getSelectedIndex)))
        ; Go back to an untabbed interface if there's only one tab left
        (if (= 1 (-> content .getTabCount))
          (-> window (.setContentPane (-> content (.getSelectedComponent)))))))))

(defn- bind-keys
  "Attach various key bindings to an Inspector Jay window"
  [frame]
  (let
    [f1-key (KeyStroke/getKeyStroke KeyEvent/VK_F1 0)
     f3-key (KeyStroke/getKeyStroke KeyEvent/VK_F3 0)
     f4-key (KeyStroke/getKeyStroke KeyEvent/VK_F4 0)
     shift-f3-key (KeyStroke/getKeyStroke KeyEvent/VK_F3 InputEvent/SHIFT_DOWN_MASK)
     ctrl-f-key (KeyStroke/getKeyStroke KeyEvent/VK_F (-> (Toolkit/getDefaultToolkit) .getMenuShortcutKeyMask)) ; Cmd on a Mac, Ctrl elsewhere
     ctrl-w-key (KeyStroke/getKeyStroke KeyEvent/VK_W (-> (Toolkit/getDefaultToolkit) .getMenuShortcutKeyMask))] 
    ; Search javadoc for the currently selected node and open it in a browser window
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (open-javadoc (get-jtree (get-selected-tab frame)))))
                             f1-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; (Re)invoke selected method
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (reinvoke (get-jtree (get-selected-tab frame)))))
                             f4-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Find next
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (let [tab (get-selected-tab frame)]
                                   (search-tree-and-select (get-jtree tab) (-> (get-search-field tab) .getText) true false))))
                             f3-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Find previous
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (let [tab (get-selected-tab frame)]
                                   (search-tree-and-select (get-jtree tab) (-> (get-search-field tab) .getText) false false))))
                             shift-f3-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Go to search field (or back to the tree)
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (let [tab (get-selected-tab frame)
                                       search-field (get-search-field tab)]
                                   (if (-> search-field .hasFocus)
                                     (-> (get-jtree tab) .requestFocus)
                                     (-> search-field .requestFocus)))))
                             ctrl-f-key JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Close the current tab
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [e]
                                 (close-tab frame (get-selected-tab frame))))
                             ctrl-w-key JComponent/WHEN_IN_FOCUSED_WINDOW))))

(defn inspector-panel
  "Create and show an Inspector Jay window to inspect a given object.
   See default-options for more information on all available keyword arguments."
  ^JPanel [^Object object & {:as args}]
  (let [obj-info (seesaw/text :multi-line? true :editable? false :font (gui-options :font))
        obj-tree (seesaw/tree :model (model/tree-model object args))
        crumbs (seesaw/label :icon (seesaw/icon (io/resource "icons/toggle_breadcrumb.gif")))
        obj-info-scroll (seesaw/scrollable obj-info)
        obj-tree-scroll (seesaw/scrollable obj-tree)
        split-pane (seesaw/top-bottom-split obj-info-scroll obj-tree-scroll :divider-location 1/5)
        toolbar (tool-panel object obj-tree split-pane args)
        main-panel (seesaw/border-panel :north toolbar :south crumbs :center split-pane)]
    (-> split-pane (.setDividerSize 9))
    (-> obj-info-scroll (.setBorder (border/empty-border)))
    (-> obj-tree-scroll (.setBorder (border/empty-border)))
    (doto obj-tree
      (.setCellRenderer (tree-renderer))
      (.addTreeSelectionListener (tree-selection-listener obj-info crumbs))
      (.addTreeExpansionListener (tree-expansion-listener obj-info))
      (.addTreeWillExpandListener (tree-will-expand-listener (:vars args)))
      (.setSelectionPath (-> obj-tree (.getPathForRow 0))))
    main-panel))

(defn- close-window
  "Clean up when closing a window"
  [window]
  ; Resume any threads that might've been paused by inspectors in this window
  (let [content (-> window .getContentPane)
        isTabbed (instance? JTabbedPane content)]
    (if (not isTabbed)
      (resume-execution content)
      (doseq [x (range 0 (.getTabCount content))]
        (resume-execution (.getComponentAt content x)))))
  ; Clean up any resources associated with the window
  (swap! jay-windows (fn [x] (remove (fn [w] (= (:window w) window)) x)))
  (if (empty? @jay-windows) ; If all windows are closed, reset last-selected-node
    (swap! last-selected-node (fn [x] nil))) 
  (node/clear-memoization-caches))

(defn- window-title
  "Compose a window's title"
  [window-name object]
  (let [prefix (if (nil? window-name) "Object inspector : " (str window-name " : "))]
    (str prefix (nprops/to-string (new TreeNode {:value object}))
         ;(.toString object)
         )))

(defn inspector-window 
  "Show an Inspector Jay window to inspect a given object.
   See default-options for more information on all available keyword arguments."
  [object & {:as args}]
  (let [merged-args (merge default-options args)
        win-name (:window-name merged-args) 
        panel (apply inspector-panel object (utils/map-to-keyword-args merged-args))]
    (seesaw/invoke-later
      (if (or ; Create a new window if: there are no windows yet, or :new-window is used, or a window with the given name hasn't been created yet.
              (= 0 (count @jay-windows))
              (and (:new-window merged-args) (nil? win-name))
              (and (not (nil? win-name)) (not-any? (fn [w] (= win-name (:name w))) @jay-windows)))
        ; Create a new window
        (let [window (seesaw/frame :title (window-title win-name object)
                                   :size [(gui-options :width) :by (gui-options :height)]
                                   :on-close :dispose)] 
          (swap! jay-windows (fn [x] (conj x {:window window :name win-name}))) 
          (seesaw/config! window :content panel)
          (bind-keys window)
          ; When the window is closed, remove the window from the list and clear caches
          (seesaw/listen window :window-closed (fn [e] (close-window window)))
          (-> window seesaw/show!)
          (-> (get-jtree panel) .requestFocus))
        
        ; Otherwise, add a new tab
        (let [window (if (nil? win-name)
                       (:window (last @jay-windows)) ; The most recently created window
                       (some 
                         (fn [w] (when
                                   (= win-name (:name w))
                                   (:window w))) @jay-windows))
              content (-> window .getContentPane)
              isTabbed (instance? JTabbedPane content)]
          ; If the tabbed pane has not been created yet
          (if (not isTabbed)
            (let [tabs (seesaw/tabbed-panel)
                  title (utils/truncate (-> (get-jtree content) .getModel .getRoot .getValuePreview .toString) 20)]
              (-> tabs (.setBorder (border/empty-border :top 1 :left 2 :bottom 1 :right 0)))
              (-> tabs (.add title content))
              (-> window (.setContentPane tabs))
              ; When switching tabs, adjust the window title, and focus on the tree
              (seesaw/listen tabs :change (fn [e]
                                            (let [tree (get-jtree (get-selected-tab window))
                                                  title (window-title 
                                                          win-name
                                                          (-> tree .getModel .getRoot .getValuePreview))]
                                              (-> window (.setTitle title))
                                              (-> tree .requestFocus))))))
          (let [tabs (-> window .getContentPane)]
            ; Add the new tab
            (doto tabs
              (.add (utils/truncate (.toString object) 20) panel)
              (.setSelectedIndex (-> window .getContentPane (.indexOfComponent panel))))
            ; Close the first tab, if going beyond the maximum number of tabs
            (if (> (-> tabs .getTabCount) (:max-tabs gui-options))
              (close-tab window (-> tabs (.getComponentAt 0))))
            (-> (get-jtree panel) .requestFocus)))))
    ; If desired, pause the current thread (while the GUI keeps running in a seperate thread) 
    (if (:pause args)
      (locking panel (.wait panel)))))