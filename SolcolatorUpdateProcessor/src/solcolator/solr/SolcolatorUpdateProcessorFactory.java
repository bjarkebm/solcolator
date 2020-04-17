package solcolator.solr;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import solcolator.io.IOFactoryWithoutReflection;
import solcolator.io.api.ISolcolatorResultsWriter;
import solcolator.io.api.IQueryReader;
import solcolator.luwak.LuwakQueriesManager;
import solcolator.percolator.common.IScheduledTask;
import solcolator.percolator.common.ScheduledTaskExecutor;

import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CloseHook;
import org.apache.solr.core.SolrCore;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorFactory;
import org.apache.solr.util.plugin.SolrCoreAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SolcolatorUpdateProcessorFactory  extends UpdateRequestProcessorFactory implements SolrCoreAware {
	private final static Logger log = LoggerFactory.getLogger(SolcolatorUpdateProcessorFactory.class);
	
	private SolcolatorUpdateProcessorConfiguration config;
	private ExecutorService execService = Executors.newCachedThreadPool();
	private LuwakQueriesManager manager;
	private ScheduledTaskExecutor scheduledTaskExecutor;
		
	@SuppressWarnings("rawtypes")
	@Override
	public void init(NamedList args) {		
		super.init(args);
		
		config = new SolcolatorUpdateProcessorConfiguration(args);
		
		try {
			manager = LuwakQueriesManager.getQueriesAndCapsManager();
			
			IQueryReader queryReader = IOFactoryWithoutReflection.instance().getQueryReader(config.getSolcolatorInputKind());
			queryReader.init(config.getInputConfig());
			
			List<ISolcolatorResultsWriter> solcolatorResultsWriters = IOFactoryWithoutReflection.instance().getSolcolatorResultsWriters(config.getSolcolatorOutputKinds());
			for (ISolcolatorResultsWriter solcolatorResultsWriter : solcolatorResultsWriters) {
				solcolatorResultsWriter.init(config.getOutputConfig());
			}

			List<String> componentsToParser = config.getComponents();
			
			manager.init(queryReader, solcolatorResultsWriters, componentsToParser);
			scheduledTaskExecutor = new ScheduledTaskExecutor(new IScheduledTask() {				
				
				@Override
				public void scheduledMethod() {
					manager.updateAllQueries();
					
				}
			}, config.getTargetHour(), config.getTargetMin(), config.getTargetSec());
		} catch (Exception e) {
			String errMessage = String.format("Creating manager is failed due to %s", e);
			log.error(errMessage);
			
			throw new IllegalArgumentException(errMessage);
		}
	}

	@Override
	public UpdateRequestProcessor getInstance(SolrQueryRequest solrQueryRequest, SolrQueryResponse solrQueryResponse, UpdateRequestProcessor next) {
		return new SolcolatorUpdateProcessor(next, execService, manager, config.getMatcherFactory());
    }

	@Override
	public void inform(SolrCore core) {
		manager.createMonitor(core);
		Map<String, String> reqHandlerMetadata = SolcolatorQueriesRequestHander.getRequestHandlerMetadata(core);
		manager.loadQueriesToSolcolator(reqHandlerMetadata);
		
		scheduledTaskExecutor.startExecutionAt();
		
		core.addCloseHook(new CloseHook() {
			
			@Override
			public void preClose(SolrCore core) { }
			
			@Override
			public void postClose(SolrCore core) {
				try {
		    		execService.shutdown();
		    		execService.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
				} catch (InterruptedException e) {
					log.error("Stopping of executors service is interrupted");
				} finally {
					if (!execService.isTerminated()) {
						log.error("Stopping of executors service is interrupted: cancel non-finished tasks");
					}

					execService.shutdownNow();
				}	
				
				manager.close();
			}
		});
	}
}