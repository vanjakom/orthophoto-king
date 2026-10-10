(ns orthophoto-king.core
  (:require
   [clj-common.http-server :as http-server]
   [clj-common.json :as json]
   [clj-common.localfs :as fs]
   [clj-common.path :as path]
   compojure.core
   compojure.route
   [orthophoto-king.metadata :as metadata]
   [orthophoto-king.store :as store]))

(def port 6080)

(defn- json-response
  ([object]
   (json-response 200 object))
  ([status object]
   {
    :status status
    :headers {"Content-Type" "application/json; charset=utf-8"}
    :body (json/write-to-string object)}))

(defn- error-response [status message image-path]
  (json-response status {:error message :path (path/path->string image-path)}))

(def parameter-keys
  [:longitude-center :latitude-center :longitude-span :latitude-span :rotation])

(defn- body->parameters
  "Returns nil if any parameter is missing or not a number"
  [body]
  (let [parameters (json/read-keyworded body)]
    (when (every? #(number? (get parameters %)) parameter-keys)
      (into {} (map (fn [key] [key (double (get parameters key))]) parameter-keys)))))

(defn- image->list-entry [image]
  {
   :id (:id image)
   :path (path/path->string (:image image))
   :parameters (:parameters image)
   ;; false means parameters are still ones extracted from exif
   :parameters-set (some? (:updated image))
   :updated (:updated image)
   ;; nil if gimbal pitch is unknown
   :tilted (metadata/tilted? (:metadata image))
   :gimbal-pitch (get-in image [:metadata :gimbal-pitch])})

;; routes taking image use rest of url as image path
;; /data/add/Users/vanja/dataset-git/orthophoto/DJI_0001.JPG
(defn- request->image-path [request]
  (path/string->path (get-in request [:params :*])))

(def handler
  (compojure.core/routes
   (compojure.core/GET
    "/tool/align/*"
    _
    ;; image path is taken from url by page itself
    ;; read on each request to pick up changes
    {
     :status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (slurp (clojure.java.io/resource "align.html"))})
   (compojure.core/GET
    "/data/list"
    _
    (json-response (map image->list-entry (store/image-list))))
   (compojure.core/GET
    "/data/add/*"
    request
    (let [image-path (request->image-path request)]
      (if (fs/exists? image-path)
        (json-response (image->list-entry (store/image-add image-path)))
        (error-response 404 "image not found" image-path))))
   (compojure.core/GET
    "/data/orthophoto/*"
    request
    (let [image-path (request->image-path request)]
      (if (store/image-get image-path)
        {
         :status 200
         :headers {"Content-Type" "image/jpeg"}
         :body (new java.io.File (path/path->string image-path))}
        (error-response 404 "image not in store" image-path))))
   (compojure.core/GET
    "/data/retrieve/*"
    request
    (let [image-path (request->image-path request)]
      (if-let [image (store/image-get image-path)]
        (json-response (image->list-entry image))
        (error-response 404 "image not in store" image-path))))
   (compojure.core/POST
    "/data/update/*"
    request
    (let [image-path (request->image-path request)]
      (if (store/image-get image-path)
        (if-let [parameters (body->parameters (:body request))]
          (do
            (store/parameters-set image-path parameters)
            (json-response (image->list-entry (store/image-get image-path))))
          (error-response 400 "invalid parameters" image-path))
        (error-response 404 "image not in store" image-path))))
   ;; without fallback jetty fails with "Response map is nil"
   (compojure.route/not-found
    (json/write-to-string {:error "not found"}))))

(defn start []
  ;; var is passed to pick up handler redefinitions
  (http-server/create-server port #'handler))

#_(start)
