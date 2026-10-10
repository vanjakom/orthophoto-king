(ns orthophoto-king.metadata
  (:require
   [clj-common.path :as path]))

;; extraction of initial alignment parameters from drone image metadata
;; based on photo-map.core/extract-image-info

;; all parameters are in degrees, span is before rotation, rotation is
;; clockwise, 0 is north up

;; 35mm film diagonal, used with 35mm equivalent focal length to get FOV
(def film-35mm-diagonal-mm 43.27)

(def meters-per-degree-latitude 111320.0)

;; used for parameters exif is not able to provide
(def default-span-meters 100.0)

;; max allowed deviation of gimbal pitch from -90 ( nadir ) before image is
;; reported as tilted
(def tilt-threshold-degrees 5.0)

(defn- directory [metadata directory-class]
  (first (.getDirectoriesOfType metadata directory-class)))

(defn- string->double [value]
  (when value
    (try
      (Double/parseDouble (.trim value))
      (catch NumberFormatException _ nil))))

(defn- xmp-properties
  "Map of all XMP properties, DJI stores flight data there
  ( drone-dji:RelativeAltitude, drone-dji:GimbalYawDegree ... )"
  [metadata]
  (reduce
   (fn [properties xmp-directory]
     (into properties (.getXmpProperties xmp-directory)))
   {}
   (.getDirectoriesOfType metadata com.drew.metadata.xmp.XmpDirectory)))

(defn- extract-from-metadata [metadata]
  (let [gps (directory metadata com.drew.metadata.exif.GpsDirectory)
        geolocation (when gps (.getGeoLocation gps))
        exif (directory metadata com.drew.metadata.exif.ExifSubIFDDirectory)
        jpeg (directory metadata com.drew.metadata.jpeg.JpegDirectory)
        xmp (xmp-properties metadata)]
    {
     :longitude (when geolocation (.getLongitude geolocation))
     :latitude (when geolocation (.getLatitude geolocation))
     ;; above sea level, not usable for span
     :altitude (when gps
                 (.getDoubleObject gps com.drew.metadata.exif.GpsDirectory/TAG_ALTITUDE))
     ;; above take off point
     :relative-altitude (string->double (get xmp "drone-dji:RelativeAltitude"))
     :gimbal-yaw (string->double (get xmp "drone-dji:GimbalYawDegree"))
     :gimbal-pitch (string->double (get xmp "drone-dji:GimbalPitchDegree"))
     :flight-yaw (string->double (get xmp "drone-dji:FlightYawDegree"))
     :focal-length-35mm (when exif
                          (.getInteger
                           exif
                           com.drew.metadata.exif.ExifSubIFDDirectory/TAG_35MM_FILM_EQUIV_FOCAL_LENGTH))
     :width (or
             (when jpeg
               (.getInteger jpeg com.drew.metadata.jpeg.JpegDirectory/TAG_IMAGE_WIDTH))
             (when exif
               (.getInteger exif com.drew.metadata.exif.ExifSubIFDDirectory/TAG_EXIF_IMAGE_WIDTH)))
     :height (or
              (when jpeg
                (.getInteger jpeg com.drew.metadata.jpeg.JpegDirectory/TAG_IMAGE_HEIGHT))
              (when exif
                (.getInteger exif com.drew.metadata.exif.ExifSubIFDDirectory/TAG_EXIF_IMAGE_HEIGHT)))}))

(defn- read-metadata
  "Returns nil if metadata could not be read, defaults will be used"
  [image-path]
  (try
    (com.drew.imaging.ImageMetadataReader/readMetadata
     (new java.io.File (path/path->string image-path)))
    (catch Exception _ nil)))

(defn extract
  "Reads raw values from image metadata. Any value could be nil."
  [image-path]
  (if-let [metadata (read-metadata image-path)]
    (extract-from-metadata metadata)
    {}))

(defn ground-size
  "Calculates [width-meters height-meters] of area covered by nadir image.
  Uses 35mm equivalent focal length, diagonal FOV is split by aspect ratio.
  Returns nil if needed data is missing."
  [{:keys [relative-altitude focal-length-35mm width height]}]
  (when (and relative-altitude focal-length-35mm width height
             (> relative-altitude 0) (> focal-length-35mm 0))
    (let [diagonal-meters (/ (* relative-altitude film-35mm-diagonal-mm)
                             focal-length-35mm)
          diagonal-pixels (Math/sqrt (+ (* width width) (* height height)))]
      [(* diagonal-meters (/ width diagonal-pixels))
       (* diagonal-meters (/ height diagonal-pixels))])))

(defn tilted?
  "True if camera was not looking straight down, span estimate assumes nadir
  image. Returns nil if gimbal pitch is unknown."
  [{:keys [gimbal-pitch]}]
  (when gimbal-pitch
    (> (Math/abs (+ gimbal-pitch 90.0)) tilt-threshold-degrees)))

(defn normalize-rotation
  "Keeps rotation in [0, 360), yaw from DJI is in [-180, 180]"
  [rotation]
  (mod rotation 360.0))

(defn metadata->parameters
  "Converts extracted metadata to alignment parameters, falls back to
  defaults for anything missing. Center defaults to 0, 0."
  [{:keys [longitude latitude width height gimbal-yaw flight-yaw] :as metadata}]
  (let [latitude (or latitude 0.0)
        longitude (or longitude 0.0)
        [width-meters height-meters] (or
                                      (ground-size metadata)
                                      (if (and width height (> width 0))
                                        [default-span-meters
                                         (* default-span-meters (/ height width))]
                                        [default-span-meters default-span-meters]))]
    {
     :longitude-center longitude
     :latitude-center latitude
     :longitude-span (/ width-meters
                        (* meters-per-degree-latitude
                           (Math/cos (Math/toRadians latitude))))
     :latitude-span (/ height-meters meters-per-degree-latitude)
     :rotation (normalize-rotation (or gimbal-yaw flight-yaw 0.0))}))

(defn image->parameters [image-path]
  (metadata->parameters (extract image-path)))

#_(extract ["Users" "vanja" "dataset-git" "orthophoto" "DJI_0001.JPG"])
#_(image->parameters ["Users" "vanja" "dataset-git" "orthophoto" "DJI_0001.JPG"])
