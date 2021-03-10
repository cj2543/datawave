package datawave.ingest.util.cache;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.google.common.collect.Lists;
import datawave.ingest.util.ConfigurationFileHelper;
import datawave.ingest.util.cache.converter.HadoopPathConverter;
import datawave.ingest.util.cache.converter.ShortConverter;
import datawave.ingest.util.cache.mode.ClasspathMode;
import datawave.ingest.util.cache.mode.LoadJobCacheMode;
import datawave.ingest.util.cache.converter.ModeConverter;
import datawave.ingest.util.cache.mode.ModeOptions;
import datawave.ingest.util.cache.path.FileSystemPath;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Load job cache launcher will copy local configuration and jars file to a configurable timestamped location in hdfs
 */
public class LoadJobCacheLauncher {
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadJobCacheLauncher.class);
    
    public static final String JOB_CACHE_TIMESTAMP_FORMAT = "yyyyMMddHHmmss";
    public static final DateTimeFormatter JOB_CACHE_FORMATER = DateTimeFormatter.ofPattern(JOB_CACHE_TIMESTAMP_FORMAT).withZone(ZoneOffset.UTC);
    
    @Parameter(names = {"--cache-replication-cnt"}, description = "The number of replicas for loaded cache files.", converter = ShortConverter.class)
    short cacheReplicationCnt = 3;
    
    @Parameter(names = {"--finalize-load"}, description = "Finalize loading by moving working path to its final path.", arity = 1)
    boolean finalizeLoad = true;
    
    @Parameter(names = {"--executor-thread-cnt"}, description = "Number of cpu threads to use for loading the cache.")
    int executorThreadCnt = 30;
    
    @Parameter(names = {"--hadoop-conf-dirs"}, description = "The hadoop configuration directories")
    List<String> hadoopConfDirs = Arrays.asList(System.getenv("INGEST_HADOOP_CONF"), System.getenv("WAREHOUSE_HADOOP_CONF"));
    
    @Parameter(names = {"-h", "-help", "--help", "-?"}, help = true, description = "Prints job options.")
    boolean help;
    
    @Parameter(names = {"--load-mode"}, description = "Mode to determine how to find files to load the cache.", converter = ModeConverter.class)
    LoadJobCacheMode loadMode = new ClasspathMode();
    
    @Parameter(names = {"--output-paths"}, description = "The output directories to load", converter = HadoopPathConverter.class, required = true)
    List<Path> outputPaths;
    
    @Parameter(names = {"--sub-dir"}, description = "A optional subdirectory to add to the cache directory.")
    String subDir;
    
    @Parameter(names = {"--timestamp-dir"}, description = "The timestamp cache directory name.")
    String timestampDir = "jobCache_" + JOB_CACHE_FORMATER.format(LocalDateTime.now());
    
    /**
     * Will load job cache from specified arguments
     *
     * @param options
     *            Mode options that will determine the files to load to cache
     */
    public void run(ModeOptions options) {
        LOGGER.info("Converting hadoop dirs to configuration objects");
        Collection<FileSystemPath> fsPaths = Lists.newArrayList();
        Collection<Configuration> hadoopConfs = ConfigurationFileHelper.getHadoopConfs(hadoopConfDirs);
        
        try {
            LOGGER.info("Constructing file system paths");
            fsPaths = getFileSystemPaths(outputPaths, hadoopConfs);
            LOGGER.info("Finding files to load");
            Collection<String> filesToUpload = loadMode.getFilesToLoad(options);
            if (!filesToUpload.isEmpty()) {
                LOGGER.info("Loading job cache with timestamp {}", timestampDir);
                LoadJobCache.load(fsPaths, filesToUpload, finalizeLoad, cacheReplicationCnt, executorThreadCnt, timestampDir, subDir);
            } else {
                LOGGER.warn("No files were found to load cache for mode {} with options {} ", loadMode.getMode(), options);
            }
        } finally {
            LOGGER.info("Closing file system objects");
            fsPaths.forEach(FileSystemPath::close);
        }
    }
    
    private Collection<FileSystemPath> getFileSystemPaths(Collection<Path> paths, Collection<Configuration> confs) {
        // @formatter:off
        return paths
                .stream()
                .map(path -> new FileSystemPath(path, confs))
                .collect(Collectors.toList());
        // @formatter:on
    }
    
    public static void main(String[] args) throws Exception {
        LOGGER.info("Starting load job cache utility");
        LoadJobCacheLauncher launcher = new LoadJobCacheLauncher();
        ModeOptions modeOptions = new ModeOptions();
        
        JCommander jCommander = JCommander.newBuilder().addObject(launcher).addObject(modeOptions).build();
        try {
            jCommander.parse(args);
            if (launcher.help) {
                jCommander.usage();
                System.exit(-1);
            }
            launcher.run(modeOptions);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jCommander.usage();
            System.exit(-1);
        }
        LOGGER.info("Finished load job cache utility");
    }
}
