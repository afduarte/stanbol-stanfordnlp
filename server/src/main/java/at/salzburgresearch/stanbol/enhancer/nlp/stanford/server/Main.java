package at.salzburgresearch.stanbol.enhancer.nlp.stanford.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Iterator;
import java.util.Locale;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.stanbol.enhancer.servicesapi.ContentItemFactory;
import org.apache.wink.server.internal.servlet.RestServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import at.salzburgresearch.stanbol.enhancer.nlp.stanford.analyser.LangPipeline;
import at.salzburgresearch.stanbol.enhancer.nlp.stanford.analyser.StanfordNlpAnalyzer;
import at.salzburgresearch.stanbol.enhancer.nlp.stanford.web.Constants;
import at.salzburgresearch.stanbol.enhancer.nlp.stanford.web.StanfordNlpApplication;


public class Main {
    
    private static final Logger log = LoggerFactory.getLogger(Main.class);
    
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_ANALYSER_THREADS = 10;
    private static final File DEFAULT_CONFIG_DIR = new File("config");
    
    private static final Options options;

    private static final String DEFAULT_PIPELINE_CONIG = "defaultconfig/pipelines.zip";
    static {
        options = new Options();
        options.addOption("h", "help", false, "display this help and exit");
        options.addOption("p","port",true, 
            "The port for the server (default: "+DEFAULT_PORT+")");
        options.addOption("t","analyser-threads",true,
            "The size of the thread pool used for Talismane to tokenize and "
            + "POS tag sentences (default: "+DEFAULT_ANALYSER_THREADS+")");
        options.addOption("c","config-dir", true,
            "Path to the directory with the '{lang}.pipeline' configuration files "
            + "(default: "+DEFAULT_CONFIG_DIR.getAbsolutePath()+")");
    }
    /**
     * @param args
     * @throws Exception 
     */
    public static void main(String[] args) throws Exception {
        CommandLineParser parser = new PosixParser();
        CommandLine line = parser.parse(options, args);
        args = line.getArgs();
        if(line.hasOption('h')){
            printHelp();
            System.exit(0);
        }
        log.info("Starting Stanbol Talismane Server ...");
        //create the Threadpool
        log.info(" > Initialise Talismane");
        ExecutorService executor = Executors.newFixedThreadPool(
            getInt(line, 't', DEFAULT_ANALYSER_THREADS));
        
        File configDir = line.hasOption('c') ? new File(line.getOptionValue('c')) : DEFAULT_CONFIG_DIR;
        if(!configDir.isDirectory()){
            if(!line.hasOption('c')) {
                initDefaultConfig(configDir);
            } else {
                log.error("The config directory '{}' does not exist or is not a directory", configDir);
                System.exit(0);
            }
        }
        Collection<File> configFiles = (Collection<File>)FileUtils.listFiles(
            configDir, new String[]{"pipeline"}, false);
        if(configFiles.isEmpty()){
            log.error("The config directory '{}' does not contain a single '{lang}.pipeline' definition file!");
            System.exit(0);
        }
        log.info(" - initialise {} configured language(s)", configFiles.size());
        StanfordNlpAnalyzer analyzer = new StanfordNlpAnalyzer(executor, null);
        for(File configFile : configFiles){
            LangPipeline pipeline = new LangPipeline(configFile.getAbsolutePath());
            analyzer.setPipeline(pipeline.getLanguage(), pipeline);
        }
        
        //init the Jetty Server
        int port = getInt(line,'p',DEFAULT_PORT);
        log.info(" > Initialise Jetty Server on Port {}",port);
        Server server = new Server(port);
        
        log.info(" ... JAX-RS Application");
        //init the Servlet and the ServletContext
        log.info(" ... configure Servlet handler");
        ServletContextHandler handler = new ServletContextHandler();
        //Apache Wink
        ServletHolder holder = new ServletHolder(RestServlet.class);
        //Jersey
        //ServletHolder holder = new ServletHolder(new ServletContainer());
        holder.setInitParameter("javax.ws.rs.Application", StanfordNlpApplication.class.getName());
        handler.addServlet(holder, "/*");
        handler.setContextPath("/");
        server.setHandler(handler);
        //now initialise the servlet context
        handler.setAttribute(Constants.SERVLET_ATTRIBUTE_CONTENT_ITEM_FACTORY, 
            lookupService(ContentItemFactory.class));
        handler.setAttribute(Constants.SERVLET_ATTRIBUTE_STANFORD_NLP, analyzer);
        
        log.info(" ... starting server");
        server.start();
        log.info(" ... server started");
        System.out.println(" ... server started");
        try {
            server.join();
        }catch (InterruptedException e) {
        }
        log.info(" ... shutting down");
      System.out.println(" ... shutting down");
        executor.shutdown();
    }

    /**
     * @param configDir
     * @throws IOException
     */
    private static void initDefaultConfig(File configDir) throws IOException {
        log.info(" ... init defautl server configuration");
        if(!configDir.mkdirs()){
            log.error("Unable to create configuration directory {}",configDir.getAbsolutePath());
            System.exit(0);
        }
        InputStream in = Main.class.getClassLoader().getResourceAsStream(DEFAULT_PIPELINE_CONIG);
        if(in == null){
            log.error("Unable to initialise default annotation pipeline configurations. "
                + "Resource '"+DEFAULT_PIPELINE_CONIG+"' not found in classpath");
            System.exit(0);
        }
        ZipInputStream zin = new ZipInputStream(in);
        ZipEntry entry;
        while((entry = zin.getNextEntry()) != null){
            String name = FilenameUtils.getName(entry.getName()).toLowerCase(Locale.ROOT);
            if(FilenameUtils.getExtension(name).equals("pipeline")){
                File outFile = new File(configDir,name);
                log.info("  > copying {}",name);
                OutputStream out = FileUtils.openOutputStream(outFile);
                IOUtils.copy(zin, out);
                IOUtils.closeQuietly(out);
            }
        }
        IOUtils.closeQuietly(zin);
    }
    
    private static int getInt(CommandLine line, char option, int defaultValue){
        String value = line.getOptionValue(option);
        if(value != null){
            return Integer.parseInt(value);
        } else {
            return defaultValue;
        }
    }
    
    private static <T> T lookupService(Class<T> clazz){
        ServiceLoader<T> loader = ServiceLoader.load(clazz);
        Iterator<T> services = loader.iterator();
        if(services.hasNext()){
            return services.next();
        } else {
            throw new IllegalStateException("Unable to find implemetnation for service "+clazz);
        }
    }
    
    /**
     * 
     */
    private static void printHelp() {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp(
            "java -Xmx{size} -jar at.salzburgresearch.stanbol.enhancer.nlp.talismane.server-*" +
            "-jar-with-dependencies.jar [options]",
            "Indexing Commandline Utility: \n"+
            "  size:  Heap requirements depend on the dataset and the configuration.\n"+
            "         1024m should be a reasonable default.\n",
            options,
            null);
    }

}
