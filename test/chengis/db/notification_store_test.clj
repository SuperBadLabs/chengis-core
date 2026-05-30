(ns ^:integration chengis.db.notification-store-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [chengis.db.connection :as conn]
            [chengis.db.migrate :as migrate]
            [chengis.db.notification-store :as notification-store]
            [clojure.java.io :as io]))

(def test-db-path "/tmp/chengis-notification-store-test.db")

(defn setup-db [f]
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file)))
  (migrate/migrate! test-db-path)
  (f)
  (let [db-file (io/file test-db-path)]
    (when (.exists db-file) (.delete db-file))))

(use-fixtures :each setup-db)

;; ---------------------------------------------------------------------------
;; save-notification! + list-notifications
;; ---------------------------------------------------------------------------

(deftest save-and-list-notifications-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save-notification! returns the notification map"
      (let [notification (notification-store/save-notification! ds
                                                                {:build-id "build-1"
                                                                 :type :email
                                                                 :status :pending
                                                                 :details "Sending build notification"})]
        (is (some? (:id notification)))
        (is (= "build-1" (:build-id notification)))
        (is (= :email (:type notification)))
        (is (= :pending (:status notification)))))

    (testing "list-notifications returns saved notifications"
      (let [notifications (notification-store/list-notifications ds "build-1")]
        (is (= 1 (count notifications)))
        (is (= "email" (:type (first notifications))))
        (is (= "pending" (:status (first notifications))))
        (is (= "Sending build notification" (:details (first notifications))))))

    (testing "list-notifications returns empty for unknown build"
      (is (empty? (notification-store/list-notifications ds "nonexistent"))))))

(deftest save-multiple-notifications-test
  (let [ds (conn/create-datasource test-db-path)]
    (notification-store/save-notification! ds
                                           {:build-id "build-2" :type :email :status :pending :details "Email 1"})
    (notification-store/save-notification! ds
                                           {:build-id "build-2" :type :slack :status :pending :details "Slack 1"})
    (notification-store/save-notification! ds
                                           {:build-id "build-other" :type :email :status :sent :details "Other"})

    (testing "list-notifications returns only notifications for the given build"
      (let [notifications (notification-store/list-notifications ds "build-2")]
        (is (= 2 (count notifications)))
        (is (= #{"email" "slack"} (set (map :type notifications))))))

    (testing "notifications are ordered by created_at ascending"
      (let [notifications (notification-store/list-notifications ds "build-2")]
        ;; email was inserted first, slack second
        (is (= "email" (:type (first notifications))))
        (is (= "slack" (:type (second notifications))))))))

;; ---------------------------------------------------------------------------
;; update-notification-status!
;; ---------------------------------------------------------------------------

(deftest update-notification-status-test
  (let [ds (conn/create-datasource test-db-path)
        notification (notification-store/save-notification! ds
                                                            {:build-id "build-3" :type :email
                                                             :status :pending :details "Initial"})]

    (testing "update-notification-status! changes status"
      (notification-store/update-notification-status! ds (:id notification) :sent)
      (let [updated (first (notification-store/list-notifications ds "build-3"))]
        (is (= "sent" (:status updated)))
        ;; sent-at should be populated when status is :sent
        (is (some? (:sent-at updated)))))

    (testing "update-notification-status! can update to :failed with details"
      ;; Create a second notification
      (let [n2 (notification-store/save-notification! ds
                                                      {:build-id "build-3" :type :slack
                                                       :status :pending :details "Slack msg"})]
        (notification-store/update-notification-status! ds (:id n2) :failed
                                                        :details "SMTP connection refused")
        (let [notifications (notification-store/list-notifications ds "build-3")
              failed-n (first (filter #(= "slack" (:type %)) notifications))]
          (is (= "failed" (:status failed-n)))
          (is (= "SMTP connection refused" (:details failed-n))))))))

;; ---------------------------------------------------------------------------
;; Round-trip: save -> list -> update -> list-verify
;; ---------------------------------------------------------------------------

(deftest notification-round-trip-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "full notification lifecycle"
      ;; Save
      (let [n (notification-store/save-notification! ds
                                                     {:build-id "build-rt" :type :email
                                                      :status :pending :details "Build started"})]
        (is (some? (:id n)))

        ;; List
        (let [notifications (notification-store/list-notifications ds "build-rt")]
          (is (= 1 (count notifications)))
          (is (= "pending" (:status (first notifications)))))

        ;; Update
        (notification-store/update-notification-status! ds (:id n) :sent)

        ;; Verify
        (let [notifications (notification-store/list-notifications ds "build-rt")]
          (is (= "sent" (:status (first notifications))))
          (is (some? (:sent-at (first notifications)))))))))

(deftest save-notification-defaults-test
  (let [ds (conn/create-datasource test-db-path)]
    (testing "save-notification! defaults status to :pending in DB when nil"
      (notification-store/save-notification! ds
                                             {:build-id "build-defaults" :type :console
                                              :status nil :details nil})
      (let [stored (first (notification-store/list-notifications ds "build-defaults"))]
        (is (= "pending" (:status stored)))))))
