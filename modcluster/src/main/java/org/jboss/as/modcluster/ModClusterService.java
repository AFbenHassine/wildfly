/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.modcluster;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.tomcat.util.modeler.Registry;
import org.jboss.as.web.WebServer;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.modcluster.catalina.CatalinaEventHandlerAdapter;
import org.jboss.modcluster.config.ModClusterConfig;
import org.jboss.modcluster.load.LoadBalanceFactorProvider;
import org.jboss.modcluster.load.impl.DynamicLoadBalanceFactorProvider;
import org.jboss.modcluster.load.impl.SimpleLoadBalanceFactorProvider;
import org.jboss.modcluster.load.metric.LoadContext;
import org.jboss.modcluster.load.metric.LoadMetric;
import org.jboss.modcluster.load.metric.impl.ActiveSessionsLoadMetric;
import org.jboss.modcluster.load.metric.impl.AverageSystemLoadMetric;
import org.jboss.modcluster.load.metric.impl.BusyConnectorsLoadMetric;
import org.jboss.modcluster.load.metric.impl.ConnectionPoolUsageLoadMetric;
import org.jboss.modcluster.load.metric.impl.HeapMemoryUsageLoadMetric;
import org.jboss.modcluster.load.metric.impl.ReceiveTrafficLoadMetric;
import org.jboss.modcluster.load.metric.impl.RequestCountLoadMetric;
import org.jboss.modcluster.load.metric.impl.SendTrafficLoadMetric;
import org.jboss.modcluster.load.metric.impl.SystemMemoryUsageLoadMetric;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.Service;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;

/**
 * Service configuring and starting modcluster.
 *
 * @author Jean-Frederic Clere
 */
class ModClusterService implements ModCluster, Service<ModCluster> {

    private static final Logger log = Logger.getLogger("org.jboss.as.modcluster");

    static final ServiceName NAME = ServiceName.JBOSS.append("mod-cluster");

    private ModelNode modelconf;

    private CatalinaEventHandlerAdapter adapter;
    private LoadBalanceFactorProvider load;

    private final InjectedValue<WebServer> webServer = new InjectedValue<WebServer>();

    /* Depending on configuration we use one of the other */
    private org.jboss.modcluster.ModClusterService service;
    private ModClusterConfig config;
    public ModClusterService(ModelNode modelconf) {
        this.modelconf = modelconf;
    }

    /** {@inheritDoc} */
    public synchronized void start(StartContext context) throws StartException {
        log.debugf("Starting Mod_cluster Extension");

        config = new ModClusterConfig();
        // Set the configuration.
        final ModelNode proxyconf = modelconf.get(CommonAttributes.PROXY_CONF);
        final ModelNode httpdconf = proxyconf.get(CommonAttributes.HTTPD_CONF);
        final ModelNode nodeconf = proxyconf.get(CommonAttributes.NODES_CONF);

        // Set some defaults...
        if (!httpdconf.hasDefined(CommonAttributes.PROXY_LIST)) {
            config.setAdvertise(true);
        }
        config.setAdvertisePort(23364);
        config.setAdvertiseGroupAddress("224.0.1.105");
        config.setAutoEnableContexts(true);
        config.setStopContextTimeout(10);
        config.setSocketTimeout(20000);

        // Read node to set configuration.
        if (httpdconf.hasDefined(CommonAttributes.ADVERTISE_SOCKET)) {
            // TODO: That should be a socket-binding....
            config.setAdvertisePort(23364);
            config.setAdvertiseGroupAddress("224.0.1.105");
            config.setAdvertise(true);
        }
        if (httpdconf.hasDefined(CommonAttributes.SSL)) {
            // TODO: Add SSL logic.
        }
        if (httpdconf.hasDefined(CommonAttributes.ADVERTISE))
            config.setAdvertise(httpdconf.get(CommonAttributes.ADVERTISE).asBoolean());
        if (httpdconf.hasDefined(CommonAttributes.PROXY_LIST)) {
            config.setProxyList(httpdconf.get(CommonAttributes.PROXY_LIST).asString());
        }
        if (httpdconf.hasDefined(CommonAttributes.PROXY_URL))
            config.setProxyList(httpdconf.get(CommonAttributes.PROXY_URL).asString());
        if (httpdconf.has(CommonAttributes.ADVERTISE_SECURITY_KEY))
            config.setProxyList(httpdconf.get(CommonAttributes.ADVERTISE_SECURITY_KEY).asString());

        if (nodeconf.hasDefined(CommonAttributes.EXCLUDED_CONTEXTS))
            config.setExcludedContexts(nodeconf.get(CommonAttributes.EXCLUDED_CONTEXTS).asString());
        if (nodeconf.hasDefined(CommonAttributes.AUTO_ENABLE_CONTEXTS))
            config.setAutoEnableContexts(nodeconf.get(CommonAttributes.AUTO_ENABLE_CONTEXTS).asBoolean());
        if (nodeconf.hasDefined(CommonAttributes.STOP_CONTEXT_TIMEOUT)) {
            config.setStopContextTimeout(nodeconf.get(CommonAttributes.SOCKET_TIMEOUT).asInt());
            config.setStopContextTimeoutUnit(TimeUnit.SECONDS);
        }
        if (nodeconf.hasDefined(CommonAttributes.SOCKET_TIMEOUT))
            config.setSocketTimeout(nodeconf.get(CommonAttributes.SOCKET_TIMEOUT).asInt());

        // Read the metrics configuration.
        final ModelNode loadmetric = modelconf.get(CommonAttributes.LOAD_METRIC);

        if (loadmetric.hasDefined(CommonAttributes.SIMPLE_LOAD_PROVIDER)) {
            // TODO it seems we don't support that stuff.
            // LoadBalanceFactorProvider implementation, org.jboss.modcluster.load.impl.SimpleLoadBalanceFactorProvider.
            final ModelNode node = loadmetric.get(CommonAttributes.SIMPLE_LOAD_PROVIDER);
            SimpleLoadBalanceFactorProvider myload = new SimpleLoadBalanceFactorProvider();
            myload.setLoadBalanceFactor(node.get(CommonAttributes.FACTOR).asInt(1));
            load = myload;
        }

        Set<LoadMetric<LoadContext>> metrics = new HashSet<LoadMetric<LoadContext>>();
        if (loadmetric.hasDefined(CommonAttributes.DYNAMIC_LOAD_PROVIDER)) {
            final ModelNode node = loadmetric.get(CommonAttributes.DYNAMIC_LOAD_PROVIDER);
            int decayFactor = node.get(CommonAttributes.DECAY).asInt(512);
            int history = node.get(CommonAttributes.HISTORY).asInt(512);
            // We should have bunch of load-metric and/or custom-load-metric here.
            // TODO read the child nodes or what ....String nodes = node.
            if (node.hasDefined(CommonAttributes.LOAD_METRIC)) {
                final ModelNode nodemetric = node.get(CommonAttributes.LOAD_METRIC);
                final List<ModelNode> array = nodemetric.asList();
                addLoadMetrics(metrics, array);
             }
            if (node.hasDefined(CommonAttributes.CUSTOM_LOAD_METRIC)) {
                final ModelNode nodemetric = node.get(CommonAttributes.CUSTOM_LOAD_METRIC);
                final List<ModelNode> array = nodemetric.asList();
                addCustomLoadMetrics(metrics, array);

            }
            if (!metrics.isEmpty()) {
                DynamicLoadBalanceFactorProvider loader = new DynamicLoadBalanceFactorProvider(metrics);
                loader.setDecayFactor(decayFactor);
                loader.setHistory(history);
                load = loader;
            }
        }

        if (load == null) {
            // Use a default one...
            log.info("Mod_cluster uses default load balancer provider");
            SimpleLoadBalanceFactorProvider myload = new SimpleLoadBalanceFactorProvider();
            myload.setLoadBalanceFactor(1);
            load = myload;
        }
        service = new org.jboss.modcluster.ModClusterService(config, load);
        adapter = new CatalinaEventHandlerAdapter(service, webServer.getValue().getServer(), webServer.getValue().getService());
        try {
            adapter.start();
        } catch (JMException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    /** {@inheritDoc} */
    public synchronized void stop(StopContext context) {
        // TODO need something...
        if (adapter != null)
            try {
                adapter.stop();
            } catch (JMException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        adapter = null;
    }

    @Override
    public synchronized ModCluster getValue() throws IllegalStateException, IllegalArgumentException {
        return this;
    }

    MBeanServer getMBeanServer() {
        return Registry.getRegistry(null, null).getMBeanServer();
    }

    private void addLoadMetrics(Set<LoadMetric<LoadContext>> metrics, List<ModelNode> array) {
        Iterator<ModelNode> it= array.iterator();

        while(it.hasNext()) {
            final ModelNode node= (ModelNode)it.next();
            int capacity = node.get(CommonAttributes.CAPACITY).asInt(512);
            int weight = node.get(CommonAttributes.WEIGHT).asInt(9);
            String type = node.get(CommonAttributes.TYPE).asString();
            Class<? extends LoadMetric> loadMetricClass = null;
            LoadMetric<LoadContext> metric = null;

            //  SourcedLoadMetric
            if (type.equals("cpu"))
                loadMetricClass = AverageSystemLoadMetric.class;
            if (type.equals("mem"))
                loadMetricClass = SystemMemoryUsageLoadMetric.class;

            if (type.equals("heap"))
                loadMetricClass = HeapMemoryUsageLoadMetric.class;

            // MBeanAttributeLoadMetric...
            if (type.equals("sessions"))
                loadMetricClass = ActiveSessionsLoadMetric.class;
            if (type.equals("receive-traffic"))
                loadMetricClass = ReceiveTrafficLoadMetric.class;
            if (type.equals("send-traffic"))
                loadMetricClass = SendTrafficLoadMetric.class;
            if (type.equals("requests"))
                loadMetricClass = RequestCountLoadMetric.class;

            // MBeanAttributeRatioLoadMetric
            if (type.equals("connection-pool"))
                loadMetricClass = ConnectionPoolUsageLoadMetric.class;
            if (type.equals("busyness"))
                loadMetricClass = BusyConnectorsLoadMetric.class;


            if (loadMetricClass != null) {
                try {
                    metric = loadMetricClass.newInstance();
                    metric.setCapacity(capacity);
                    metric.setWeight(weight);
                    metrics.add(metric);
                } catch (InstantiationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }


    private void addCustomLoadMetrics(Set<LoadMetric<LoadContext>> metrics, List<ModelNode> array) {
        Iterator<ModelNode> it= array.iterator();
        while(it.hasNext()) {
            final ModelNode node= (ModelNode)it.next();
            int capacity = node.get(CommonAttributes.CAPACITY).asInt(512);
            int weight = node.get(CommonAttributes.WEIGHT).asInt(9);
            String name = node.get(CommonAttributes.CLASS).asString();
            Class<? extends LoadMetric> loadMetricClass = null;
            LoadMetric<LoadContext> metric = null;
            try {
                loadMetricClass = (Class<? extends LoadMetric>) this.getClass().getClassLoader().loadClass(name);
            } catch (ClassNotFoundException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }
            if (loadMetricClass != null) {
                try {
                    metric = loadMetricClass.newInstance();
                    metric.setCapacity(capacity);
                    metric.setWeight(weight);
                    metrics.add(metric);
                } catch (InstantiationException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                } catch (IllegalAccessException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }
    }

    public Injector<WebServer> getWebServer() {
        return webServer;
    }

    Registry getRegistry() {
        return Registry.getRegistry(null, null);
    }
    void registerObject(MBeanServer mbeanServer, String name, Object obj, String classname) {
        if (mbeanServer != null) {
            ObjectName objectName;
            try {
                objectName = new ObjectName(name);
                getRegistry().registerComponent(obj, objectName, classname);
            } catch (Exception e) {
                return;
            }
        }
    }

    @Override
    public Map<InetSocketAddress, String> getProxyInfo() {
        return service.getProxyInfo();
    }
}
