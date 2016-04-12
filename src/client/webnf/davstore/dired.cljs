(ns webnf.davstore.dired
  (:require
   [om.core :as om]
   [om-tools.dom :as dom]
   [webnf.mui.components :as mc]
   [webnf.mui.styles :refer [with-style style-class]]
   [webnf.mui.base :refer [state-setter] :refer-macros [component]]
   [webnf.mui.colors :refer [get-color]]
   [webnf.util :refer [log log-pr xhr] :refer-macros [forcat]]
   [davstore.client.dav :as dav]))

(defn table-renderer [& {:keys [styles columns]}]
  (let [thead (dom/thead {:class (style-class (:thead styles))}
                         (dom/tr (map #(dom/th (:capt %)) columns)))
        tdata (apply juxt (map #(comp dom/td (:data %)) columns))
        tr (fn [o] (dom/tr (tdata o)))]
    (fn [lst]
      (dom/table
       {:class (style-class (:table styles))}
       thead
       (dom/tbody
        {:class (style-class (:tbody styles))}
        (map tr lst))))))

(defn human-size [n]
  (cond
    (< n 1000) (str n)
    (< n 1000000) (str (int (/ n 1000)) "K")
    :else (str (int (/ n 1000000)) "M")))

(def file-table
  (table-renderer
   :styles {:table {[:td] {:padding (px 10)}
                    [:th] {:text-align :center}}
            :tbody {[:tr] {:border-top "1px solid black"}}}
   :columns [{:capt "Name"
              :data (fn [{:keys [dav/displayname dav/resourcetype path]}]
                      (dom/a {:onClick #(set-display-path! path)}
                             displayname
                             (when (= :collection resourcetype)
                               "/")))}
             {:capt "Size"
              :data (comp human-size :dav/getcontentlength)}
             {:capt "Type"
              :data (fn [{:keys [dav/getcontenttype dav/resourcetype]}]
                      (if (= :collection resourcetype)
                        "folder"
                        getcontenttype))}
             {:capt "Last Modified"
              :data (comp str :dav/getlastmodified)}
             {:capt "Created"
              :data (comp str :dav/creationdate)}]))

(defn path-widget [cursor]
  (let [path (:current-display-path cursor)]
    (mc/paper
     {:size 1 :style {:margin {:left (px 20) :bottom (px 30)}}}
     (when (seq path)
       (mc/paper
        {:style {:padding {:top 0 :bottom 0 :left (px 20) :right (px 20)}
                 :background-color "rgba(255,255,255,0.9)"}}
        (dom/span
         {:style #js{:lineHeight "86px"}}
         "// " (:out (reduce (fn [{:keys [path out]} name]
                               {:path (conj path name)
                                :out (conj out (dom/a {:on-click (fn [_] (om/update! cursor :current-display-path
                                                                                     (conj path name)))}
                                                      name "/ "))})
                             {:path [] :out []} (butlast path)))
         (last path) "/ ")
        (when-let [t (:editing-new cursor)]
          (mc/text-field
           :place-holder (if (= :folder t)
                           "Folder Name"
                           "File Name")
           :floating-label true
           :grab-focus true
           :on-blur (fn [_] (om/update! cursor :editing-new nil))
           :on-enter (fn [e]
                       (let [entry-name (.. e -target -value)]
                         (if (str/blank? entry-name)
                           (om/update! cursor :editing-new nil)
                           (if (= :folder t)
                             (let [tree (:tree cursor)
                                   path* (dav/subpath (:current-display-path cursor) entry-name)]
                               (go
                                 (let [{:keys [status]} (<! (dav/mkcol! tree path*))]
                                   (if (= 201 status)
                                     (let [st (<! (dav/subtree tree path*))]
                                       (om/update! cursor (cons :tree path*) st)
                                       (set-display-path! path*))
                                     (js/alert "Error."))
                                   (om/update! cursor :editing-new nil))))
                             (let [fname (if (.endsWith entry-name ".html")
                                           entry-name (str entry-name ".html"))
                                   path* (dav/subpath (:current-display-path cursor)
                                                      fname)]
                               (om/update! cursor :editing-new nil)
                               (om/update! cursor :current-display-path path*)
                               (om/update! cursor (cons :tree path*)
                                           (assoc-in
                                            (dav/dummy-file path* "text/html")
                                            [nil :html] "<p/>")))))))))
        (when (= :folder (:editing-new cursor))
          "/")))
     (let [btn-style {:color (get-color :green :p500)}]
       (mc/paper {:style {:vertical-align :top
                          :margin {:left (px 20)}}}
                 (mc/button {:style btn-style
                             :on-click #(om/update! cursor :editing-new :folder)}
                            "Create Folder")
                 (dom/br)
                 (mc/button {:style btn-style
                             :on-click #(om/update! cursor :editing-new :file)}
                            "Create Page"))))))

(defelement folder [info children]
  (mc/paper
   {:style {:margin {:left (px 20) :top (px 20)}}}
   (path-widget info)
   (file-table (sort-by :dav/displayname children))))
