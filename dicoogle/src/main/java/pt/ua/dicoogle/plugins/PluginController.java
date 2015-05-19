/**
 * Copyright (C) 2014  Universidade de Aveiro, DETI/IEETA, Bioinformatics Group - http://bioinformatics.ua.pt/
 *
 * This file is part of Dicoogle/dicoogle.
 *
 * Dicoogle/dicoogle is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Dicoogle/dicoogle is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Dicoogle.  If not, see <http://www.gnu.org/licenses/>.
 */
package pt.ua.dicoogle.plugins;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.apache.commons.configuration.ConfigurationException;
import org.restlet.resource.ServerResource;

import pt.ua.dicoogle.sdk.IndexerInterface;
import pt.ua.dicoogle.sdk.JettyPluginInterface;
import pt.ua.dicoogle.sdk.PluginSet;
import pt.ua.dicoogle.sdk.QueryInterface;
import pt.ua.dicoogle.sdk.StorageInputStream;
import pt.ua.dicoogle.sdk.StorageInterface;
import pt.ua.dicoogle.sdk.core.PlatformCommunicatorInterface;
import pt.ua.dicoogle.sdk.datastructs.QueryReport;
import pt.ua.dicoogle.sdk.datastructs.Report;
import pt.ua.dicoogle.sdk.settings.ConfigurationHolder;
import pt.ua.dicoogle.sdk.task.Task;
import pt.ua.dicoogle.taskManager.TaskManager;

/**
 *
 * PluginController is the core of the Plugins architecture.
 *
 * <p>
 * It loads the plugins, takes care of the list of active plugins and control
 * the tasks that are exchanged between plugins and core plugins
 *
 * @author Carlos Ferreira
 * @author Frederico Valente
 * @author Luís A. Bastião Silva <bastiao@ua.pt>
 * @author Tiago Marques Godinho.
 */
public class PluginController {

    private static final Logger logger = Logger.getLogger("dicoogle");

    private Collection<PluginSet> pluginSets;
    private File pluginFolder;
    private static PluginController mainGlobalInstance;
    private TaskManager taskManager = new TaskManager(4);

    public static PluginController get() { //TODO:remove this sinful bastard
        return mainGlobalInstance;
    }

    public PluginController(File pathToPluginDirectory) {
        logger.info("Creating PluginController Instance");
        pluginFolder = pathToPluginDirectory;
        pluginSets = new ArrayList<>();
        mainGlobalInstance = this;

        //the plugin directory does not exist. lets create it
        if (!pathToPluginDirectory.exists()) {
            logger.info("Creating new Plugin Folder");
            pathToPluginDirectory.mkdirs();
        }

        //loads the plugins
        pluginSets = PluginFactory.getPlugins(pathToPluginDirectory);
        logger.info("Loaded Local Plugins");

        //loads plugins' settings and passes them to the plugin
        File settingsFolder = new File(pluginFolder.getPath() + "/settings/");
        if (!settingsFolder.exists()) {
            logger.info("Creating Local Settings Folder");
            settingsFolder.mkdir();
        }

        //load settings
        for (PluginSet plugin : pluginSets) {
            logger.info("loading: " + plugin.getName());

            File pluginSettingsFile = new File(settingsFolder + "/" + plugin.getName() + ".xml");
            try {
                ConfigurationHolder holder = new ConfigurationHolder(pluginSettingsFile);
                plugin.setSettings(holder);
            }
            catch (ConfigurationException e) {
                logger.throwing("PluginController","Contructor",e);
            }
        }

        //TODO: this has no business being here. either is a plugin and should have its project
        //or it is not and should be added from the outside to the controller
        pluginSets.add(new DefaultFileStoragePlugin());
        logger.info("Added default storage plugin");

        initializePlugins(pluginSets);
        logger.info("finished plugin initialization");
    }

    /*
     *   Plugins may need to communicate with the platform.
     *   Plugins implementing the PlatformCommunicator interface have a setPlatformProxy
     *   which expects an implementation of the methods which it may call (given by PlatformCommunicatorInterface)
     *   This method insures all plugins implementing the interface now have
     *   a viable reference to those methods
     *
     */
    private void initializePlugins(Iterable<PluginSet> plugins) {
        for (PluginSet set : plugins) {
            if (set instanceof PlatformCommunicatorInterface) {
                ((PlatformCommunicatorInterface) set).setPlatformProxy(new DicooglePlatformProxy(this));
            }
        }
    }

        /**
     * Stops the plugins and saves the settings
     *
     */
    public void shutdown() throws IOException {
        for (PluginSet plugin : pluginSets) {
            plugin.shutdown();
        }
    }
    
    public Collection<JettyPluginInterface> getJettyPlugins() {
        ArrayList<JettyPluginInterface> jettyInterfaces = new ArrayList<>();
        for (PluginSet pluginSet : pluginSets) {
            Collection<JettyPluginInterface> jettyInterface = pluginSet.getJettyPlugins();
            if (jettyInterface == null) {
                continue;
            }
            jettyInterfaces.addAll(jettyInterface);
        }
        return jettyInterfaces;
    }

    public Collection<ServerResource> getRestPlugins() {
        ArrayList<ServerResource> restInterfaces = new ArrayList<>();
        for (PluginSet pluginSet : pluginSets) {
            Collection<ServerResource> restInterface = pluginSet.getRestPlugins();
            if (restInterface == null) {
                continue;
            }
            restInterfaces.addAll(restInterface);
        }
        return restInterfaces;
    }

    public Collection<StorageInterface> getStoragePlugins(boolean onlyEnabled) {
        ArrayList<StorageInterface> storagePlugins = new ArrayList<>();
        for (PluginSet pSet : pluginSets) {
            for (StorageInterface store : pSet.getStoragePlugins()) {
                if (!store.isEnabled() && onlyEnabled) {
                    continue;
                }
                storagePlugins.add(store);
            }
        }
        return storagePlugins;
    }

    /**
     * Resolve a URI to a DicomInputStream
     *
     * @param location
     * @return
     */
    public Iterable<StorageInputStream> resolveURI(URI location) {
        for (StorageInterface store : getStoragePlugins(true)) {
            if (store.handles(location)) {
                return store.at(location);
            }
        }

        logger.info("Could not resolve uri: " + location.toString());
        return Collections.emptyList();
    }

    /**
     * TODO: this can be heavily improved if we keep a map of scheme->indexer
     * However we are not supposed to call this every other cycle.
     *
     * returns null if no suitable plugin is found TODO: we should return a
     * proxy storage that always returns error
     *
     * @param location only the scheme matters
     * @return
     */
    public StorageInterface getStorageForSchema(URI location) {

        for (StorageInterface store : getStoragePlugins(false)) {
            if (store.handles(location)) {
                return store;
            }
        }
        logger.info("Could not get storage for schema: " + location.toString());
        return null;
    }

    public StorageInterface getStorageForSchema(String schema) {
        URI uri = null;
        try {
            uri = new URI(schema, "", "");
        }
        catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            logger.throwing("PluginController","getStorageFromSchema",e);
            return null;
        }
        return getStorageForSchema(uri);
    }

    
    
    public Iterable<IndexerInterface> getIndexingPlugins(boolean onlyEnabled) {
        ArrayList<IndexerInterface> indexers = new ArrayList<>();
        for (PluginSet pSet : pluginSets) {
            for (IndexerInterface index : pSet.getIndexPlugins()) {
                if (!index.isEnabled() && onlyEnabled) {
                    continue;
                }
                indexers.add(index);
            }
        }
        return indexers;
    }

    public Iterable<QueryInterface> getQueryPlugins(boolean onlyEnabled) {
        ArrayList<QueryInterface> queriers = new ArrayList<>();
        for (PluginSet pSet : pluginSets) {
            for (QueryInterface querier : pSet.getQueryPlugins()) {
                if (!querier.isEnabled() && onlyEnabled) {
                    continue;
                }
                queriers.add(querier);
            }
        }
        return queriers;
    }
    
    public IndexerInterface getIndexerByName(String name, boolean onlyEnabled) {
        for (IndexerInterface p : getIndexingPlugins(onlyEnabled)) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        logger.info("unable to retrieve indexer:" + name);
        return null;
    }
    public QueryInterface getQueryProviderByName(String name, boolean onlyEnabled) {
        for (QueryInterface p : getQueryPlugins(onlyEnabled)) {
            if (p.getName().equalsIgnoreCase(name)) {
                return p;
            }
        }
        logger.info("unable to retrieve query provider:" + name);
        return null;
    }

    public Iterable<String> queryNames(boolean enabled) {        
        ArrayList<String> ret = new ArrayList<>();
        for (QueryInterface p :  getQueryPlugins(enabled)) {
            ret.add(p.getName());
        }
        return ret;
    }

    public Iterable<String> indexerNames() {
        ArrayList<String> names = new ArrayList<>();
        for (IndexerInterface p : getIndexingPlugins(true)) {
            names.add(p.getName());
        }
        return names;
    }

    
    
    
    
    
    public Task<Report> queryDispatch(String querySource, final String query, final Object... parameters) {
        Task<Report> t = queryClosure(querySource, query, parameters);
        taskManager.dispatch(t);
        return t;//returns the handler to obtain the computation results
    }

    public Task<Report> queryDispatch(final Iterable<String> querySources, final String query, final Object... parameters) {

        /*
         * Creates a task that dispatches several tasks, one per enabled query plugin,
         * waits their completion and merges the results into a single report.
         * A dispatched task object is returned.
         */
        Task<Report> queryTask = new Task<>("multiple query -> "+query, () -> {
            ArrayList<Task<Report>> tasks = new ArrayList<>();
            for (String sourcePlugin : querySources) {
                Task<Report> task = queryClosure(sourcePlugin, query, parameters);
                tasks.add(task);
                taskManager.dispatch(task);
            }
            
            Report q = new QueryReport();
            for (Task<Report> t : tasks) {
                q.addChild(t.get());
            }
            return q;
        });

        //and executes said task asynchronously
        taskManager.dispatch(queryTask);
        return queryTask;
    }

    public Task<Report> queryClosure(String querySource, final String query, final Object... parameters) {
        final QueryInterface queryEngine = getQueryProviderByName(querySource, true);
        //returns a tasks that runs the query from the selected query engine
        Task<Report> queryTask = new Task<>("query:"+querySource+" -> "+query,
                new Callable<Report>() {
                    @Override
                    public Report call() throws Exception {
                        if (queryEngine == null) {
                            return QueryReport.EmptyReport;
                        }
                        return queryEngine.query(query, parameters);
                    }
                });
        return queryTask;
    }

        //returns a task, that has not yet been dispatched
    //this means the task can run in blocking mode on the caller thread, or
    //be dispatched to the task manager
    public Task<Report> queryClosure(final Iterable<String> querySources, final String query, final Object... parameters) {
        /*
         * Creates a task that dispatches several tasks, one per enabled query plugin,
         * waits their completion and merges the results into a single report.
         * A dispatched task object is returned.
         */
        Task<Report> queryTask = new Task<>("multiple query:",
                new Callable<Report>() {
                    @Override
                    public Report call() throws Exception {
                        ArrayList<Task<Report>> tasks = new ArrayList<>();
                        for (String sourcePlugin : querySources) {
                            Task<Report> task = queryClosure(sourcePlugin, query, parameters);
                            tasks.add(task);
                            taskManager.dispatch(task);
                        }

                        Report q = new QueryReport();
                        for (Task<Report> t : tasks) {
                            q.addChild(t.get());
                        }
                        return q;

                    }
                });
        return queryTask;
    }

    /*
     * Given an URI (which may be a path to a dir or file, a web resource or whatever)
     * this method creates a task that
     * calls the appropriate indexers and instructs them to index the data pointed to by the URI
     * it is up to the caller to run the task asynchronously by feeding it to an executor
     * or in a blocking way by calling the get() method of the task
     */
    public Task<Report> indexAllClosure(URI path) {
        logger.info("Starting indexing procedure for " + path.toString());
        StorageInterface store = getStorageForSchema(path);

        if (store == null) {
            logger.info("No storage plugin detected");
            return Task.error("No storage plugin detected");
        }

        
        Task<Report> retTask = new Task<>("indexall task: "+path, new Callable<Report>() {

            @Override
            public Report call() throws Exception {
                ArrayList<Task<Report>> subtasks = new ArrayList<>();
                for (IndexerInterface indexer : getIndexingPlugins(true)) {
                    if(indexer.handles(path)){
                        Task<Report> task = indexer.index(store.at(path));
                        task.onCompletion(() -> {logger.info(indexer.getName()+": finished indexing");});
                        taskManager.dispatch(task);
                        subtasks.add(task);
                    }
                }
                
                Report ret = new Report();
                for(Task<Report> task: subtasks){
                    ret.addChild(task.get());
                }
                
                return ret;
            }
        });
        return retTask;
    }
    
    public Task<Report> indexAllDispatch(URI path) {
        Task task = indexAllClosure(path);
        taskManager.dispatch(task);
        return task;
    }
    /*
    *   Creates a task that will index URI with the plugin name
    *   and dispatches it to the executor
    */
    public Task<Report> indexDispatch(String pluginName, URI path) {
        Task t = indexClosure(pluginName, path);
        taskManager.dispatch(t);
        return t;
    }

    public Task<Report> indexClosure(String pluginName, URI path) {
        logger.info("Starting Indexing procedure from Closure for " + path.toString());
        StorageInterface store = getStorageForSchema(path);

        if (store == null) {
            logger.info("No storage plugin detected");
            return null;
        }

        IndexerInterface indexer = getIndexerByName(pluginName, true);
        if (indexer == null) {
            String names = "";
            for (String s : indexerNames()) {
                names += s + " ";
            }
            logger.info("Indexer not found:" + pluginName + "\n available:" + names);
            return Task.error("Indexer not found:" + pluginName + "\n available:" + names);
        }

        final String pathF = path.toString();
        if(indexer.handles(path)){
            Task<Report> task = indexer.index(store.at(path));
            task.onCompletion(() -> {logger.info("index task accomplished: " + task.getName());});
            return task;
        }
        else{
            return new Task<Report>(() -> {return Report.error(pathF.toString()+" is not handled by "+pluginName, null);});
        }

        
    }

    public void unindex(URI path) {
        logger.info("unindexing: "+ path.toString());
        StorageInterface store = getStorageForSchema(path);

        if (store == null) {
            logger.info("No storage plugin detected");
            return;
        }

        getIndexingPlugins(true).forEach((indexer) -> {indexer.unindex(path);});
    }

    public Stream<PluginSet> plugins() {
        return pluginSets.stream();
    }

    String getDicoogleDirectory() {
        return "";
    }

}


    /**
     * Each pluginSet provides a collection of barebone rest interfaces Here we
     * check which interfaces are present and create a restlet component to
     * handle them. also we export them using common settings and security
     * profiles
     *
     * @return
     */
    /*private void initRestInterface(Collection<PluginSet> plugins) {
     System.err.println("Initialize plugin rest interfaces");

     ArrayList<ServerResource> restInterfaces = new ArrayList<>();
     for (PluginSet set : plugins) {
     Collection<ServerResource> restInterface = set.getRestPlugins();
     if (restInterface == null) {
     continue;
     }
     restInterfaces.addAll(restInterface);
     }

     for (ServerResource resource : restInterfaces) {
     DicoogleWebservice.attachRestPlugin(resource);
     }
     System.err.println("Finished initializing rest interfaces");
     }*/

    /*private void initJettyInterface(Collection<PluginSet> plugins) {
     System.err.println("initing jetty interface");
                
     ArrayList<JettyPluginInterface> jettyInterfaces = new ArrayList<>();
     for(PluginSet set : plugins){
     Collection<JettyPluginInterface> jettyInterface = set.getJettyPlugins();
     if(jettyInterface == null) continue;
     jettyInterfaces.addAll(jettyInterface);
     }
         
     DicoogleWeb jettyServer = ControlServices.getInstance().getWebServicePlatform();
     for(JettyPluginInterface resource : jettyInterfaces){
     jettyServer.addContextHandlers( resource.getJettyHandlers() );
     }
     }*/
