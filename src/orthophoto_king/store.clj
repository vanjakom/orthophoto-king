(ns orthophoto-king.store
  (:require
   [clj-common.edn :as edn]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   [orthophoto-king.metadata :as metadata]))

;; single edn file, format, id is path of image
;; {:images {path {:id path string
;;               :image path
;;               :metadata extracted metadata
;;               :parameters alignment parameters
;;               :updated timestamp of last /data/update, nil if from exif}}}

(def store-path ["Users" "vanja" "dataset-git" "orthophoto" "store.edn"])

(defn- read-store []
  (if (fs/exists? store-path)
    (with-open [is (fs/input-stream store-path)]
      (edn/read-object is))
    {:images {}}))

(def store (atom (read-store)))

(defn- write-store [state]
  (with-open [os (fs/output-stream store-path)]
    (edn/write-pprint-object os state)))

(defn- update-store [update-fn]
  (let [state (swap! store update-fn)]
    (write-store state)
    state))

(defn image-list []
  (sort-by :id (vals (:images (deref store)))))

(defn image-get [image-path]
  (get-in (deref store) [:images image-path]))

(defn image-add
  "Registers image, initial parameters are extracted from metadata, defaults
  are used for anything missing. Adding image already in store resets it's
  parameters. Returns added image."
  [image-path]
  (let [extracted (metadata/extract image-path)
        image {
               :id (path/path->string image-path)
               :image image-path
               :metadata extracted
               :parameters (metadata/metadata->parameters extracted)
               :updated nil}]
    (update-store #(assoc-in % [:images image-path] image))
    image))

(defn parameters-set [image-path parameters]
  (update-store
   #(update-in % [:images image-path] assoc
               :parameters parameters
               :updated (System/currentTimeMillis)))
  nil)

#_(image-list)
