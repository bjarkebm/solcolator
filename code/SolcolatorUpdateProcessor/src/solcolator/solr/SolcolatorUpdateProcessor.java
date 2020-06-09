package solcolator.solr;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.monitor.HighlightsMatch;
import org.apache.lucene.monitor.HighlightsMatch.Hit;
import org.apache.lucene.monitor.Monitor;
import org.apache.lucene.monitor.MultiMatchingQueries;
import org.apache.lucene.monitor.ParallelMatcher;
import org.apache.lucene.monitor.QueryMatch;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import solcolator.io.api.ISolcolatorResultsWriter;
import solcolator.luwak.LuwakMatcherFactory;
import solcolator.luwak.LuwakQueriesManager;

public class SolcolatorUpdateProcessor extends UpdateRequestProcessor {
	private static Logger log = LoggerFactory.getLogger(SolcolatorUpdateProcessor.class);
	private final ExecutorService execService;
	private final LuwakQueriesManager manager;
	private final Monitor monitor;
	private final List<ISolcolatorResultsWriter> writers;
	private final LuwakMatcherFactory factory;

	private List<Document> luwakDocs = new ArrayList<>();
	private Map<String, SolrInputDocument> solrDocs = new HashMap<>();
	private Similarity similarity;
	private Map<String, Analyzer> fieldToAnalyzer = new HashMap<>();

	public SolcolatorUpdateProcessor(UpdateRequestProcessor next, ExecutorService execService,
			LuwakQueriesManager manager, LuwakMatcherFactory factory) {
		super(next);

		this.manager = manager;
		this.execService = execService;
		this.monitor = manager.getMonitor();
		this.writers = manager.getSolcolatorResultsWriters();
		this.factory = factory;
	}

	@Override
	public void finish() throws IOException {
		execService.execute(() -> {
			matchDocumentsList(luwakDocs); // For good performance LUWAK matching would be run only when all docs in bulk
																			// had passed processAdd
		});

		super.finish();
	}

	private void matchDocumentsList(List<Document> documentsList) {
		log.info("Start to match docs through solcolator");
		long start = System.currentTimeMillis();

		try {
			matchByFactory(documentsList, factory);

			log.info(String.format("ParallelMatcher matched %d items in %d ms", documentsList.size(),
					System.currentTimeMillis() - start));
		} catch (Exception e) {
			log.error("Failed to match luwak documents", e);
		} finally {
			log.info("Finish to match docs through solcolator");
		}
	}

	private void matchByFactory(List<Document> documentsList, LuwakMatcherFactory factory) throws IOException {
		switch (factory) {
			case HIGHLIGHTING:
				highlightingMatch(documentsList);
				break;

			case SIMPLE:
				simpleMatch(documentsList);
				break;

			default:
				simpleMatch(documentsList);
				break;
		}
	}

	// TODO: To think how to union this function with highlightingMatch
	private void simpleMatch(List<Document> documentsList) throws IOException {
		Document[] docArray = documentsList.toArray(new Document[documentsList.size()]);

		MultiMatchingQueries<QueryMatch> matches = monitor.match(docArray, ParallelMatcher.factory(execService, QueryMatch.SIMPLE_MATCHER));
		Map<String, List<SolrInputDocument>> docsToWrite = new HashMap<>();

		for (ISolcolatorResultsWriter writer : writers) {
			for (Document doc : documentsList) {
				String id = doc.get("id");
				for (QueryMatch documentMatches : matches.getMatches(Integer.parseInt(id))) {
					try {
						String queryId = documentMatches.getQueryId();
						SolrInputDocument docWithSpecificFields = getDocWithSpecificFields(queryId, id, null, writer);

						List<SolrInputDocument> docs = docsToWrite.get(queryId);
						if (docs == null) {
							docs = new ArrayList<>();
							docs.add(docWithSpecificFields);
							docsToWrite.put(queryId, docs);
						} else {
							docs.add(docWithSpecificFields);
						}
					} catch (Exception e) {
						String errMessage = String.format("Failed to write matched results for doc %s", doc.get("id"));
						log.error(errMessage, e);
					}
				}
			}

			writer.writeSolcolatorResults(docsToWrite);
		}
	}

	private void highlightingMatch(List<Document> documentsList) throws IOException {
		Document[] docArray = documentsList.toArray(new Document[documentsList.size()]);

		MultiMatchingQueries<HighlightsMatch> matches = monitor.match(docArray, ParallelMatcher.factory(execService, HighlightsMatch.MATCHER));
		Map<String, List<SolrInputDocument>> docsToWrite = new HashMap<>();

		for (ISolcolatorResultsWriter writer : writers) {
			for (Document doc : documentsList) {
				String id = doc.get("id");
				for (HighlightsMatch documentMatches : matches.getMatches(Integer.parseInt(id))) {
					try {
						String queryId = documentMatches.getQueryId();
						SolrInputDocument docWithSpecificFields = getDocWithSpecificFields(queryId, id, documentMatches.getHits(),
								writer);

						List<SolrInputDocument> docs = docsToWrite.get(queryId);
						if (docs == null) {
							docs = new ArrayList<>();
							docs.add(docWithSpecificFields);
							docsToWrite.put(queryId, docs);
						} else {
							docs.add(docWithSpecificFields);
						}
					} catch (Exception e) {
						String errMessage = String.format("Failed to write matched results for doc %s", doc.get("id"));
						log.error(errMessage, e);
					}
				}
			}

			writer.writeSolcolatorResults(docsToWrite);
		}
	}

	/**
	 * Return Solr doc with specific (by config) fields only + queryId field, query
	 * and hits(optional)
	 * 
	 * @param queryId - query id
	 * @param itemId  - id of Solr doc
	 * @param hits    - hits(optional)
	 * @return SolrDocument with neccessary fields only
	 */
	private SolrInputDocument getDocWithSpecificFields(String queryId, String itemId, Map<String, Set<Hit>> hits,
			ISolcolatorResultsWriter writer) {
		Map<String, SolrInputField> specificFields = new HashMap<>();
		SolrInputDocument doc = solrDocs.get(itemId);
		List<String> fl = writer.getFl(); // fl can be different per writer (in the case where we use several writers)

		if (!fl.contains("*")) { // if fl = * then we want to get all fields
			for (String fieldName : fl) {
				specificFields.put(fieldName, doc.getField(fieldName));
			}
		} else {
			specificFields = doc.entrySet().stream().filter(x -> !x.getKey().equals("_version_"))
					.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		}

		SolrInputDocument retDoc = new SolrInputDocument(specificFields);

		// add extra fields
		retDoc.addField("queryid_s", queryId);
		retDoc.addField("query_s", manager.getQueryIdToLuwakQuery().get(queryId).getQuery());
		if (hits != null) {
			retDoc.addField("hits_s", new SolrInputField(hits.toString()));
		}

		return retDoc;
	}

	@Override
	public void processAdd(AddUpdateCommand cmd) throws IOException {
		String itemId = cmd.getIndexedId().utf8ToString();

		try {
			IndexSchema schema = cmd.getReq().getSchema();
			Document luceneDoc = cmd.getLuceneDocument();

			setSimilarity(schema.getSimilarity());

			luwakDocs.add(luceneDoc);
			solrDocs.put(itemId, cmd.getSolrInputDocument());
		} catch (Exception e) {
			String errMessage = String.format("Failed to build luwak document for item_id:%s", itemId);
			log.error(errMessage, e);
		}

		super.processAdd(cmd);
	}

	private void setSimilarity(Similarity indexSchemaSimilarity) {
		if (similarity == null) {
			similarity = indexSchemaSimilarity;
		}
	}
}
