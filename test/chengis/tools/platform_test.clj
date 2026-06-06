(ns chengis.tools.platform-test
  "Cover the OS / arch detection helpers under chengis.tools.platform.
   These read `os.name` / `os.arch` system properties via the
   `tools/getprop*` seam so we can pin both to known values without
   touching JVM globals."
  (:require [chengis.tools :as tools]
            [chengis.tools.platform :as platform]
            [clojure.test :refer [deftest is testing]]))

(defn- with-platform [{:keys [os-name os-arch]} f]
  (with-redefs [tools/getprop* (fn [k]
                                 (case k
                                   "os.name" os-name
                                   "os.arch" os-arch
                                   nil))]
    (f)))

;; ---------------------------------------------------------------------------
;; os
;; ---------------------------------------------------------------------------

(deftest detects-linux
  (with-platform {:os-name "Linux"}
    #(is (= :linux (platform/os)))))

(deftest detects-mac-via-mac-prefix
  (with-platform {:os-name "Mac OS X"}
    #(is (= :mac (platform/os)))))

(deftest detects-mac-via-darwin-substring
  (testing "older JVMs report Darwin/Mac variants — both match"
    (with-platform {:os-name "Darwin"}
      #(is (= :mac (platform/os))))))

(deftest detects-windows
  (with-platform {:os-name "Windows 10"}
    #(is (= :windows (platform/os)))))

(deftest unknown-os-falls-through
  (with-platform {:os-name "SomeNewOS"}
    #(is (= :unknown (platform/os)))))

(deftest blank-or-nil-os-falls-through
  (with-platform {:os-name nil}    #(is (= :unknown (platform/os))))
  (with-platform {:os-name ""}     #(is (= :unknown (platform/os)))))

;; ---------------------------------------------------------------------------
;; arch
;; ---------------------------------------------------------------------------

(deftest amd64-maps-to-x64
  (with-platform {:os-arch "amd64"}
    #(is (= :x64 (platform/arch)))))

(deftest x86_64-maps-to-x64
  (with-platform {:os-arch "x86_64"}
    #(is (= :x64 (platform/arch)))))

(deftest aarch64-maps-to-itself
  (with-platform {:os-arch "aarch64"}
    #(is (= :aarch64 (platform/arch)))))

(deftest arm64-maps-to-aarch64
  (with-platform {:os-arch "arm64"}
    #(is (= :aarch64 (platform/arch)))))

(deftest unknown-arch-falls-through
  (with-platform {:os-arch "sparc"}
    #(is (= :unknown (platform/arch)))))

;; ---------------------------------------------------------------------------
;; supported?
;; ---------------------------------------------------------------------------

(deftest supported-true-on-linux-x64
  (with-platform {:os-name "Linux" :os-arch "amd64"}
    #(is (true? (platform/supported?)))))

(deftest supported-true-on-mac-aarch64
  (with-platform {:os-name "Mac OS X" :os-arch "aarch64"}
    #(is (true? (platform/supported?)))))

(deftest supported-false-on-windows
  (with-platform {:os-name "Windows 11" :os-arch "amd64"}
    #(is (false? (platform/supported?)))))

(deftest supported-false-on-unknown-arch
  (with-platform {:os-name "Linux" :os-arch "sparc"}
    #(is (false? (platform/supported?)))))
