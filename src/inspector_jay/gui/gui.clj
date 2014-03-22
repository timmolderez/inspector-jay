; Copyright (c) 2013-2014 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.gui
  "Defines Inspector Jay's graphical user interface"
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
    [inspector-jay.gui
     [utils]
     [node-properties]]
    [inspector-jay.model
     [tree-node]
     [tree-model]])
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
    [net.java.balloontip.utils TimingUtils]))

(native!) ; Use the OS's native look and feel

(def gui-options ; Inspector Jay GUI options
  {:width 1024
   :height 768
   :font (font :name :sans-serif :style #{:plain})
   :crumb-length 32
   :max-tabs 20
   :btip-style `(new IsometricBalloonStyle (UIManager/getColor "Panel.background") (color "#268bd2") 5)
   :btip-error-style `(new IsometricBalloonStyle (UIManager/getColor "Panel.background") (color "#d32e27") 3)
   :btip-positioner `(new LeftAbovePositioner 8 5)})

(def tree-options ; Tree filtering options
  {:sorted true
   :methods true
   :fields true
   :public true
   :protected false
   :private false
   :static true
   :inherited true})

(defn- tree-renderer ^DefaultTreeCellRenderer []
  "Returns a cell renderer which defines what each tree node should look like"
  (proxy [DefaultTreeCellRenderer] []
    (getTreeCellRendererComponent [tree value selected expanded leaf row hasFocus]
      (proxy-super getTreeCellRendererComponent tree value selected expanded leaf row hasFocus)
      (-> this (.setText (to-string value)))
      (-> this (.setIcon (get-icon value)))
      this)))

(defn- tree-selection-listener ^TreeSelectionListener [info-panel crumbs-panel]  
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

(defn- tree-expansion-listener ^TreeExpansionListener [info-panel]
  "Updates the detailed information panel whenever a node is expanded."
  (proxy [TreeExpansionListener] []
    (treeExpanded [event]
      (config! info-panel :text (to-string-verbose (-> event .getPath .getLastPathComponent))))
    (treeCollapsed [event])))

(defn- tree-will-expand-listener ^TreeWillExpandListener [shared-vars]
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
                                                     (let [value (eval-arg (-> (nth param-boxes i) .getText) shared-vars)
                                                           type (nth param-types i)]
                                                       (cond
                                                         ; Do primitive type conversion when necessary
                                                         (= type java.lang.Short) (short value) ; Convert long to short
                                                         (= type java.lang.Float) (float value) ; Convert double to float
                                                         (= type java.lang.Integer) (int value) ; Convert long to int
                                                         :else value))
                                                     (catch Exception e 
                                                       (do
                                                         (TimingUtils/showTimedBalloon (new BalloonTip 
                                                                                         (nth param-boxes i)
                                                                                         (label (-> e .getMessage))
                                                                                         (eval (gui-options :btip-error-style))
                                                                                         (eval (gui-options :btip-positioner))
                                                                                         nil)
                                                           3000)
                                                         (-> (Toolkit/getDefaultToolkit) .beep)
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
                  (listen x :key-pressed key-handler))
                (listen cancel-button :action (fn [e] 
                                                (-> btip .closeBalloon)
                                                (-> jtree .requestFocus)))
                (listen ok-button :action ok-handler))
              (throw (new ExpandVetoException event))))))) ; Deny expanding the tree node; it will only be expanded once the value is available
    (treeWillCollapse [event])))

(defn- open-javadoc [jtree]
  "Search Javadoc for the selected node (if present)"
  (let [selection (-> jtree .getLastSelectedPathComponent)]
    (if (not= selection nil)
      (javadoc (get-javadoc-class selection)))))

(defn- reinvoke [jtree]
  "(Re)invoke the selected method node"
  (let [selection (-> jtree .getLastSelectedPathComponent)
        selection-path (-> jtree .getSelectionPath)
        selection-row (-> jtree  (.getRowForPath selection-path))]
    (if (= (-> selection .getKind) :method)
      (do 
        (-> jtree (.collapsePath selection-path))
        (-> jtree .getModel (.valueForPathChanged selection-path (object-node (new Object))))
        (-> jtree (.expandRow selection-row))
        (-> jtree (.setSelectionRow selection-row))))))

(defn- search-tree [^JTree tree ^String key start-row forward include-current]
  "Search a JTree for the first visible node whose value contains 'key', starting from the node at row 'start-row'.
   If 'forward' is true, we search going forward; otherwise we search backwards.
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
  "Search the inspector tree and select the node that was found, if any"
  (let [start-row (if (-> tree .isSelectionEmpty) 0 (-> tree .getLeadSelectionRow))
        next-match (search-tree tree text start-row forward include-current)]
    (if (not= next-match nil)
      (doto tree
        (.setSelectionPath next-match)
        (.scrollPathToVisible next-match))
      (-> (Toolkit/getDefaultToolkit) .beep))))

(defn- tool-panel ^JToolBar [^Object object ^JTree jtree ^JSplitPane split-pane]
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
        ; Refresh the inspector tree given the current options in the filter menu
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
        refresh-button (button :icon (icon (resource "icons/nav_refresh.gif")) :size iconSize)
        filter-tip (delay (new BalloonTip ; Only create the filter menu once needed
                            filter-button
                            filter-panel
                            (eval (gui-options :btip-style))
                            (eval (gui-options :btip-positioner))
                            nil))
        search-txt (text :columns 20 :text "Search...")
        toolbar (toolbar :items [sort-button filter-button pane-button doc-button invoke-button refresh-button
                                 (Box/createHorizontalGlue) search-txt (Box/createHorizontalStrut 2)])]
    (doto toolbar
      (.setBorder (empty-border :thickness 1))
      (.setFloatable false))
    (-> search-txt (.setMaximumSize (-> search-txt .getPreferredSize)))
    ; Ditch the redundant dotted rectangle when a button is focused 
    (-> sort-button (.setFocusPainted false))
    (-> filter-button (.setFocusPainted false))
    (-> pane-button (.setFocusPainted false))
    (-> doc-button (.setFocusPainted false))
    (-> invoke-button (.setFocusPainted false))
    (-> refresh-button (.setFocusPainted false))
    ; Set tooltips
    (-> sort-button (.setToolTipText "Sort alphabetically"))
    (-> filter-button (.setToolTipText "Filtering options..."))
    (-> pane-button (.setToolTipText "Toggle horizontal/vertical layout"))
    (-> doc-button (.setToolTipText "Search Javadoc (F1)"))
    (-> invoke-button (.setToolTipText "(Re)invoke selected method (F4)"))
    (-> refresh-button (.setToolTipText "Refresh tree"))
    (-> search-txt (.setToolTipText "Search visible tree nodes (F3 / Shift-F3)"))
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
    ; (Re)invoke the selected method
    (listen invoke-button :action (fn [e] (reinvoke jtree)))
    ; Refresh the tree
    (listen refresh-button :action update-filters)
    ; Clear search field initially
    (listen search-txt :focus-gained (fn [e] 
                                       (-> search-txt (.setText ""))
                                       (-> search-txt (.removeFocusListener (last(-> search-txt (.getFocusListeners)))))))
    ; When typing in the search field, look for matches
    (listen search-txt #{:remove-update :insert-update} (fn [e]
                                                          (search-tree-and-select jtree (-> search-txt .getText) true true)))
    toolbar))

(defn- get-jtree [^JPanel panel]
  "Retrieve the object tree from an inspector Jay panel"
  (let [split-pane (nth (-> panel .getComponents) 2)
        scroll-pane (nth (-> split-pane .getComponents) 2)
        viewport (nth (-> scroll-pane .getComponents) 0)
        jtree (-> viewport .getView)]
    jtree))

(defn- get-search-field [^JPanel panel]
  "Retrieve the search field from an inspector Jay panel" 
  (let [toolbar (nth (-> panel .getComponents) 0)
        search-field (nth (-> toolbar .getComponents) 7)]
    search-field))

(defn- get-selected-tab ^JPanel [^JFrame window]
  "Retrieve the currently selected tab of an inspector Jay window"
  (let [content (-> window .getContentPane)
        isTabbed (instance? JTabbedPane content)]
    (if (not isTabbed)
      content
      (-> content .getSelectedComponent))))

(defn- close-selected-tab [^JFrame window]
  "Close the currently selected tab in an Inspector Jay window"
  (let [content (-> window .getContentPane)
        isTabbed (instance? JTabbedPane content)] 
    (if (not isTabbed)
      ; Close the window if there only is one object opened
      (-> window (.dispatchEvent (new WindowEvent window WindowEvent/WINDOW_CLOSING)))
      ; Close the tab
      (do
        (-> content (.removeTabAt (-> content .getSelectedIndex)))
        ; Go back to an untabbed interface if there's only one tab left
        (if (= 1 (-> content .getTabCount))
          (-> window (.setContentPane (-> content (.getSelectedComponent)))))))))

(defn- bind-keys
  [^JFrame frame]
  "Attach various key bindings to an Inspector Jay window"
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
                                 (close-selected-tab frame)))
                             ctrl-w-key JComponent/WHEN_IN_FOCUSED_WINDOW))))

(defn inspector-panel ^JPanel [^Object object & args]
  "Create and show an Inspector Jay window to inspect a given object"
  (let [vars-index (if (not= args nil) 
                     (+ 1 (-> args (.indexOf :vars)))
                     0)
        shared-vars (if (and (not= vars-index 0) (< vars-index (count args)))
                      (nth args vars-index)
                      {})
        obj-info (text :multi-line? true :editable? false :font (gui-options :font))
        obj-tree (tree :model (tree-model object tree-options))
        crumbs (label :icon (icon (resource "icons/toggle_breadcrumb.gif")))
        obj-info-scroll (scrollable obj-info)
        obj-tree-scroll (scrollable obj-tree)
        split-pane (top-bottom-split obj-info-scroll obj-tree-scroll :divider-location 1/5)
        toolbar (tool-panel object obj-tree split-pane)
        main-panel (border-panel :north toolbar :south crumbs :center split-pane)]
    (-> split-pane (.setDividerSize 9))
    (-> obj-info-scroll (.setBorder (empty-border)))
    (-> obj-tree-scroll (.setBorder (empty-border)))
    (doto obj-tree
      (.setCellRenderer (tree-renderer))
      (.addTreeSelectionListener (tree-selection-listener obj-info crumbs))
      (.addTreeExpansionListener (tree-expansion-listener obj-info))
      (.addTreeWillExpandListener (tree-will-expand-listener shared-vars))
      (.setSelectionPath (-> obj-tree (.getPathForRow 0))))
    main-panel))

; List of all open Inspector Jay windows
(def jay-windows (new java.util.Vector))

(defn inspector-window [object & args]
  "Show an Inspector Jay window to inspect a given object.
   If a window was already open, a new tab will be created 
   (unless the :new-window keyword is passed as an optional argument)."
  (if (or (not= nil (some #{:new-window} args)) (= 0 (count jay-windows)))
      ; Create a new window
      (let [window (frame :title (str "Object inspector : " (.toString object)) 
                          :size [(gui-options :width) :by (gui-options :height)]
                          :on-close :dispose)
            panel (apply inspector-panel object args)]
        (-> jay-windows (.add window))
        (config! window :content panel)
        (bind-keys window)
        ; When the window is closed, remove the window from the list and clear caches
        (listen window :window-closed (fn [e] 
                                        (-> jay-windows (.remove window))
                                        (clear-memoization-caches)))
        (-> window show!)
        (-> (get-jtree panel) .requestFocus))
      ; Otherwise, add a new tab
      (let [window (last jay-windows)
            content (-> window .getContentPane)
            isTabbed (instance? JTabbedPane content)
            panel (apply inspector-panel object args)]
        ; If the tabbed pane has not been created yet
        (if (not isTabbed)
          (let [tabs (tabbed-panel)
                title (truncate (-> (get-jtree content) .getModel .getRoot .getValue .toString) 20)]
            (-> tabs (.setBorder (empty-border :top 1 :left 2 :bottom 1 :right 0)))
            (-> tabs (.add title content))
            (-> window (.setContentPane tabs))
            ; When switching tabs, adjust the window title, and focus on the tree
            (listen tabs :change (fn [e]
                                   (let [tree (get-jtree (get-selected-tab window))
                                         title (-> tree .getModel .getRoot .getValue .toString)]
                                     (-> window (.setTitle title))
                                     (-> tree .requestFocus))))))
        (let [tabs (-> window .getContentPane)]
          ; Add the new tab
          (doto tabs
            (.add (truncate (.toString object) 20) panel)
            (.setSelectedIndex (-> window .getContentPane (.indexOfComponent panel))))
          ; Close the first tab, if going beyond the maximum number of tabs
          (if (> (-> tabs .getTabCount) (gui-options :max-tabs))
            (-> tabs (.removeTabAt 0)))
          (-> (get-jtree panel) .requestFocus)))))