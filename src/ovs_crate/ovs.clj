(ns ovs-crate.ovs
  "Crate with functions for setting up and configuring servers using OVS"
  (:require [pallet.actions :as actions]
            [pallet.crate :as crate]
            [pallet.environment :as env]
            [pallet.utils :as utils]
            [pallet.crate :refer [defplan]]))

(defplan install-ovs-packages
  "Install all OVS packages."
  []
  (actions/packages :aptitude ["openvswitch-brcompat" "openvswitch-common"
                               "openvswitch-controller" "openvswitch-datapath-dkms"
                               "openvswitch-ipsec" "openvswitch-pki"
                               "openvswitch-switch" "openvswitch-test"]))

(defplan install-failsafe-conf
  "Install custom /etc/init/failsafe.conf to shorted boot time."
  []
  ;; shorten timeouts in /etc/init/failsafe.conf for quick reboot
  (actions/remote-file "/etc/init/failsafe.conf"
                       :local-file (utils/resource-path "ovs/failsafe.conf")
                       :literal true))

(defplan remove-ebtables
  "Remove ebtables, not needed."
  []
  (actions/exec-checked-script
   "Remove ebtables package, not needed"
   (aptitude --assume-yes purge ebtables)))

(defplan install-etc-network-interfaces
  "Install our custom /etc/network/interfaces."
  []
  (let [node-hostname (crate/target-name)
        interfaces-file (env/get-environment [:host-config node-hostname :ovs-config :interfaces-file])]
    (actions/remote-file "/etc/network/interfaces"
                         :local-file interfaces-file
                         :literal true)
    (actions/exec-checked-script
     "Restart networking"
     (service networking restart))))

(defplan create-bridge
  "Create a single OVS bridge"
  [bridge-config]
  (let [bridge-name (:name bridge-config)]
    (actions/exec-checked-script
     "Delete and recreate bridge"
     (ovs-vsctl -- --if-exists del-br ~bridge-name)
     (ovs-vsctl add-br ~bridge-name))))

(defplan add-gre-port
  "Add a GRE port for a given bridge to a given remote ip."
  [bridge-name [gre-number gre-config]]
  (let [remote-ip (:remote-ip gre-config)
        psk (:psk gre-config)
        port-name (format "gre%d" gre-number)
        options (format "options:remote_ip=%s options:psk=\"%s\"" remote-ip psk)]
    (actions/exec-checked-script
     "Add GRE port"
     (ovs-vsctl -- --if-exists del-port ~bridge-name ~port-name)
     (ovs-vsctl add-port ~bridge-name ~port-name
                -- set interface ~port-name type=ipsec_gre ~options))))

(defplan create-gre-connections
  "Create all GRE connections for a given bridge"
  [bridge-config]
  (let [bridge-name (:name bridge-config)
        gre-connections (:gre-connections bridge-config)]
    (actions/exec-checked-script
     "Delete all GRE connections"
     (doseq [port @(ovs-vsctl list-ports ~bridge-name | grep gre)]
       (ovs-vsctl del-port ~bridge-name @port)))
    (doseq [gre-vec (map vector (iterate inc 0) gre-connections)]
      (add-gre-port bridge-name gre-vec))))

(defplan ensure-forwarding-on
  "Make sure host is setup to forward IP traffic."
  []
  (actions/exec-checked-script
   "Ensure IP forwarding is on"
   (echo "1 > /proc/sys/net/ipv4/ip_forward")
   (if (= @(grep "^net.ipv4.ip_forward=1" "/etc/sysctl.conf") "")
     (echo "\"net.ipv4.ip_forward=1\" >> /etc/sysctl.conf"))))

(defplan setup-forwarding
  "Setup host to forward traffic from a given bridge and associated network."
  [bridge-config]
  (when (contains? bridge-config :act-as-forwarder)
    (let [forward-config (:act-as-forwarder bridge-config)
          {:keys [from via source]} forward-config]
      (ensure-forwarding-on)
      (actions/exec-checked-script
       "Add iptable rules for forwarding"
       (iptables -t nat -A POSTROUTING -o ~via -j MASQUERADE)
       (iptables -I INPUT 1 -i ~from -j ACCEPT)
       (iptables -A FORWARD -i ~from -s ~source -j ACCEPT)))))

(defplan setup-bridge
  "Perform all server side setup for a given OVS bridge."
  [bridge-config]
  (create-bridge bridge-config)
  (create-gre-connections bridge-config)
  (setup-forwarding bridge-config))

(defplan create-bridges
  "Setup all bridges cofigured for a given host."
  []
  (let [node-hostname (crate/target-name)
        bridges (env/get-environment [:host-config node-hostname :ovs-config :bridges])]
    (doseq [bridge-config bridges]
      (setup-bridge bridge-config))))

(defplan setup-ovs
  "Install packages for OVS and configure networking."
  []
  (install-ovs-packages)
  (install-failsafe-conf)
  (remove-ebtables)
  (install-etc-network-interfaces)
  (create-bridges))

(defplan recreate-all-gre-connections
  "Utility function to update all GRE connections on all bridges for a given host."
  []
  (let [node-hostname (crate/target-name)
        bridges (env/get-environment [:host-config node-hostname :ovs-config :bridges])]
    (doseq [bridge-config bridges]
      (create-gre-connections bridge-config))))