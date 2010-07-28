/*******************************************************************************
 * Copyright (c) 2010 Christopher Haines, Dale Scheppler, Nicholas Skaggs, Stephen V. Williams.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the new BSD license
 * which accompanies this distribution, and is available at
 * http://www.opensource.org/licenses/bsd-license.html
 * 
 * Contributors:
 *     Christopher Haines, Dale Scheppler, Nicholas Skaggs, Stephen V. Williams - initial API and implementation
 *     Christopher Barnes, Narayan Raum - scoring ideas and algorithim
 *     Yang Li - pairwise scoring algorithm
 *     Christopher Barnes - regex scoring algorithim
 ******************************************************************************/
package org.vivoweb.ingest.score;

import static java.util.Arrays.asList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import joptsimple.OptionParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.vivoweb.ingest.util.ArgList;
import org.vivoweb.ingest.util.JenaConnect;
import org.vivoweb.ingest.util.Record;
import org.vivoweb.ingest.util.RecordHandler;
import org.xml.sax.SAXException;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.RDFNode;
import com.hp.hpl.jena.rdf.model.Resource;
import com.hp.hpl.jena.rdf.model.ResourceFactory;
import com.hp.hpl.jena.rdf.model.Statement;
import com.hp.hpl.jena.rdf.model.StmtIterator;

/***
 * 
 *  VIVO Score
 *  @author Nicholas Skaggs nskaggs@ctrip.ufl.edu
 */
public class Score {
		/**
		 * Log4J Logger
		 */
		private static Log log = LogFactory.getLog(Score.class);
		/**
		 * Model for VIVO instance
		 */
		private Model vivo;
		/**
		 * Model where input is stored
		 */
		private Model scoreInput;
		/**
		 * Model where output is stored
		 */
		private Model scoreOutput;
		/**
		 * Attribute to match for exactMatch algorithim
		 */
		private List<String> matchAttribute;
		
		
		/**
		 * Main method
		 * @param args command line arguments
		 */
		public static void main(String... args) {
			
			log.info("Scoring: Start");
			try {
				new Score(new ArgList(getParser(), args, "i","V"));
			} catch(IllegalArgumentException e) {
				try {
					log.fatal(e);
					getParser().printHelpOn(System.out);
				} catch(IOException e1) {
					log.fatal(e1.getMessage(),e1);
				}
			} catch(Exception e) {
				log.fatal(e.getMessage(),e);
			}
			log.info("Scoring: End");
		}
		
		/**
		 * Get the OptionParser
		 * @return the OptionParser
		 */
		private static OptionParser getParser() {
			OptionParser parser = new OptionParser();
			parser.acceptsAll(asList("i", "rdfRecordHandler")).withRequiredArg().describedAs("rdfRecordHandler config filename");
			//TODO Nicholas: Implement individual RDF input
			//parser.acceptsAll(asList("f", "rdfFilename")).withRequiredArg().describedAs("RDF filename");
			parser.acceptsAll(asList("V", "vivoJenaConfig")).withRequiredArg().describedAs("vivoJenaConfig config filename");
			parser.acceptsAll(asList("T", "tempModelConfig")).withRequiredArg().describedAs("tempModelConfig config filename");
			parser.acceptsAll(asList("O", "outputModelConfig")).withRequiredArg().describedAs("outputModelConfig config filename");
			parser.acceptsAll(asList("e", "exactMatch")).withRequiredArg().describedAs("exact match fieldname").defaultsTo("workEmail");
			parser.acceptsAll(asList("t", "tempModel")).withRequiredArg().describedAs("temporary working model name").defaultsTo("tempModel");
			parser.acceptsAll(asList("o", "outputModel")).withRequiredArg().describedAs("output model name").defaultsTo("outputModel");
			parser.acceptsAll(asList("n","allow-non-empty-working-model"),"If set, this will not clear the working model before scoring begins");
			parser.acceptsAll(asList("r","retain-working-model"),"If set, this will not clear the working model after scoring is complete");
			return parser;
		}
		
		
		/**
		 * Constructor	
		 * @param jenaVivo model containing vivo statements
		 * @param jenaScoreInput model containing statements to be scored
		 * @param jenaScoreOutput output model
		 * @param exactMatchArg exact match attribute
		 */
		public Score(Model jenaVivo, Model jenaScoreInput, Model jenaScoreOutput, List<String> exactMatchArg) {
			this.vivo = jenaVivo;
			this.scoreInput = jenaScoreInput;
			this.scoreOutput = jenaScoreOutput;
			this.matchAttribute = exactMatchArg;
		}
		
		/**
		 * Constructor
		 * @param opts option set of parsed args
		 */
		public Score(ArgList opts) {			
			//Get optional inputs / set defaults
			//Check for config files, before parsing name options
			String workingModel = opts.get("T");
			if (workingModel == null) workingModel = opts.get("t");
			
			String outputModel = opts.get("O");
			if (outputModel == null) outputModel = opts.get("o");
			
			
			boolean allowNonEmptyWorkingModel = opts.has("n");
			boolean retainWorkingModel = opts.has("r");
			List<String> exactMatchArg = opts.getAll("e");

			try {
				log.info("Loading configuration and models");
				RecordHandler rh = RecordHandler.parseConfig(opts.get("i"));	
				
				//Connect to vivo
				JenaConnect jenaVivoDB = JenaConnect.parseConfig(opts.get("V"));
				
				//Create working model
				JenaConnect jenaTempDB = new JenaConnect(jenaVivoDB,workingModel);
				
				//Create output model
				JenaConnect jenaOutputDB = new JenaConnect(jenaVivoDB,outputModel);
				
				//Load up rdf data from translate into temp model
				Model jenaInputDB = jenaTempDB.getJenaModel();
				
				if (!jenaInputDB.isEmpty() && !allowNonEmptyWorkingModel) {
					log.warn("Working model was not empty! -- Emptying Model before execution");
					jenaInputDB.removeAll();
				}
				
				for (Record r: rh) {
					jenaInputDB.read(new ByteArrayInputStream(r.getData().getBytes()), null);
				}
				
				//Init
				Score scoring = new Score(jenaVivoDB.getJenaModel(), jenaInputDB, jenaOutputDB.getJenaModel(), exactMatchArg);
				
				//Call each exactMatch
				for (String attribute : scoring.matchAttribute) {
					scoring.exactMatch(attribute);
				}
				
			 	//Empty working model
				if (!retainWorkingModel) scoring.scoreInput.removeAll();
				//Close and done
				scoring.scoreInput.close();
				scoring.scoreOutput.close();
				scoring.vivo.close();
			} catch(ParserConfigurationException e) {
				log.fatal(e.getMessage(),e);
			} catch(SAXException e) {
				log.fatal(e.getMessage(),e);
			} catch(IOException e) {
				log.fatal(e.getMessage(),e);
			}
		}
		
		/**
		 * Executes a sparql query against a JENA model and returns a result set
		 * @param  model a model containing statements
		 * @param  queryString the query to execute against the model
		 * @return queryExec the executed query result set
		 */
		 private static ResultSet executeQuery(Model model, String queryString) {
		    	Query query = QueryFactory.create(queryString);
		    	QueryExecution queryExec = QueryExecutionFactory.create(query, model);
		    	
		    	return queryExec.execSelect();
		 }
		 
		 
		/**
		 * Commits resultset to a matched model
		 * @param  result a model containing vivo statements
		 * @param  storeResult the result to be stored
		 * @param  paperResource the paper of the resource
		 * @param  matchNode the node to match
		 * @param  paperNode the node of the paper
		 */
		 private static void commitResultSet(Model result, ResultSet storeResult, Resource paperResource, RDFNode matchNode, RDFNode paperNode) {
				RDFNode authorNode;
				QuerySolution vivoSolution;
				
				//loop thru resultset
	 	    	while (storeResult.hasNext()) {
	 	    		vivoSolution = storeResult.nextSolution();
	 	    		
	 	    		//Grab person URI
	                authorNode = vivoSolution.get("x");
	                log.info("Found " + matchNode.toString() + " for person " + authorNode.toString());
	                log.info("Adding paper " + paperNode.toString());
	
	                result.add(recursiveSanitizeBuild(paperResource,null));
	                
	                replaceResource(authorNode,paperNode, result);
	                
					//take results and store in matched model
	                result.commit();
	 	    	} 
		 }
		 
		/**
		 * Traverses paperNode and adds to toReplace model 
		 * @param  mainNode primary node
		 * @param  paperNode node of paper
		 * @param  toReplace model to replace
		 */
		private static void replaceResource(RDFNode mainNode, RDFNode paperNode, Model toReplace){
			 Resource authorship;
			 Property linkedAuthorOf = ResourceFactory.createProperty("http://vivoweb.org/ontology/core#linkedAuthor");
             Property authorshipForPerson = ResourceFactory.createProperty("http://vivoweb.org/ontology/core#authorInAuthorship");
             
             Property authorshipForPaper = ResourceFactory.createProperty("http://vivoweb.org/ontology/core#informationResourceInAuthorship");
             Property paperOf = ResourceFactory.createProperty("http://vivoweb.org/ontology/core#linkedInformationResource");
             Property rankOf = ResourceFactory.createProperty("http://vivoweb.org/ontology/core#authorRank");
             
             Resource flag1 = ResourceFactory.createResource("http://vitro.mannlib.cornell.edu/ns/vitro/0.7#Flag1Value1Thing");
             Resource authorshipClass = ResourceFactory.createResource("http://vivoweb.org/ontology/core#Authorship");
             
             Property rdfType = ResourceFactory.createProperty("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
             Property rdfLabel = ResourceFactory.createProperty("http://www.w3.org/2000/01/rdf-schema#label");
			 int authorRank = 1;	
			 
             log.info("Link paper " + paperNode.toString() + " to person " + mainNode.toString() + " in VIVO");
             authorship = ResourceFactory.createResource(paperNode.toString() + "/vivoAuthorship/l1");
             
             //string that finds the last name of the person in VIVO
             Statement authorLName = ((Resource)mainNode).getProperty(ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/lastName"));
             
             String authorQuery = "PREFIX core: <http://vivoweb.org/ontology/core#> " +
         							"PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
									"SELECT ?badNode " +
									"WHERE {?badNode foaf:lastName \"" + authorLName.getObject().toString() + "\" . " +
											"?badNode core:authorInAuthorship ?authorship . " +
											"?authorship core:linkedInformationResource <" + paperNode.toString() + "> }";
             
             log.debug(authorQuery);
             
             ResultSet killList = executeQuery(toReplace,authorQuery);
             
             while(killList.hasNext()) {
            	 QuerySolution killSolution = killList.nextSolution();
	 	    		
	 	    	 //Grab person URI
            	 Resource removeAuthor = killSolution.getResource("badNode");
	             
            	 //query the paper for the first author node (assumption that affiliation matches first author)
            	 log.debug("Delete Resource " + removeAuthor.toString());
                 
	             //return a statement iterator with all the statements for the Author that matches, then remove those statements
            	 //model.remove is broken so we are using statement.remove
	             StmtIterator deleteStmts = toReplace.listStatements(null, null, removeAuthor);
	             while (deleteStmts.hasNext()) {
	            	 Statement dStmt = deleteStmts.next();
	            	 log.debug("Delete Statement " + dStmt.toString());
	            		            	 
                 	if (!dStmt.getSubject().equals(removeAuthor)) {
                 		Statement authorRankStmt = dStmt.getSubject().getProperty(rankOf);
                 		authorRank = authorRankStmt.getObject().asLiteral().getInt();
       	                              		
                 		StmtIterator authorshipStmts = dStmt.getSubject().listProperties();
	       	            while (authorshipStmts.hasNext()) {
	       	            	 log.debug("Delete Statement " + authorshipStmts.next().toString());
	       	            }
	       	            dStmt.getSubject().removeProperties();
	       	            
	       	            StmtIterator deleteAuthorshipStmts = toReplace.listStatements(null, null, dStmt.getSubject());
	       	            while (deleteAuthorshipStmts.hasNext()) {
	       	            	Statement dASStmt = deleteAuthorshipStmts.next();
	       	            	log.debug("Delete Statement " + dASStmt.toString());
	       	            	dASStmt.remove();
	       	            }	       	            
	       	            	       	            
                 	}                 	
                 	
	             }	             
	             
	             StmtIterator authorStmts = removeAuthor.listProperties();
	             while (authorStmts.hasNext()) {
	            	 log.debug("Delete Statement " + authorStmts.next().toString());
	             }
	             removeAuthor.removeProperties();
             }
                         
             toReplace.add(authorship,linkedAuthorOf,mainNode);
             log.trace("Link Statement [" + authorship.toString() + ", " + linkedAuthorOf.toString() + ", " + mainNode.toString() + "]");
             toReplace.add((Resource)mainNode,authorshipForPerson,authorship);
             log.trace("Link Statement [" + mainNode.toString() + ", " + authorshipForPerson.toString() + ", " + authorship.toString() + "]");
             toReplace.add(authorship,paperOf,paperNode);
             log.trace("Link Statement [" + authorship.toString() + ", " + paperOf.toString() + ", " + paperNode.toString() + "]");
             toReplace.add((Resource)paperNode,authorshipForPaper,authorship);
             log.trace("Link Statement [" + paperNode.toString() + ", " + authorshipForPaper.toString() + ", " + authorship.toString() + "]");
             toReplace.add(authorship,rdfType,flag1);
             log.trace("Link Statement [" + authorship.toString() + ", " + rdfType.toString() + ", " + flag1.toString() + "]");
             toReplace.add(authorship,rdfType,authorshipClass);
             log.trace("Link Statement [" + authorship.toString() + ", " + rdfType.toString() + ", " + authorshipClass.toString() + "]");
             toReplace.add(authorship,rdfLabel,"Authorship for Paper");
             log.trace("Link Statement [" + authorship.toString() + ", " + rdfLabel.toString() + ", " + "Authorship for Paper]");
             toReplace.addLiteral(authorship,rankOf,authorRank);
             log.trace("Link Statement [" + authorship.toString() + ", " + rankOf.toString() + ", " + String.valueOf(authorRank) + "]");

             toReplace.commit();
		 }
		 
		/**
		 * Traverses paperNode and adds to toReplace model 
		 * @param mainRes the main resource
		 * @param linkRes the resource to link it to
		 * @return the model containing the sanitized info so far 
		 */
		 private static Model recursiveSanitizeBuild(Resource mainRes, Resource linkRes){
			 Model returnModel = ModelFactory.createDefaultModel();
			 Statement stmt;
			 
			 StmtIterator mainStmts = mainRes.listProperties();
			 
			 while (mainStmts.hasNext()) {
             	stmt = mainStmts.nextStatement();
              	log.trace("Statement " + stmt.toString());
			 
              	//Don't add any scoring statements
				 if (!stmt.getPredicate().toString().contains("/score")) {
	          		returnModel.add(stmt);
	          		                    	
	                 	if ((stmt.getObject().isResource() && !((Resource)stmt.getObject()).equals(linkRes)) && !((Resource)stmt.getObject()).equals(mainRes)) {
	                 		returnModel.add(recursiveSanitizeBuild((Resource)stmt.getObject(), mainRes));
	                 	}
	                 	if (!stmt.getSubject().equals(linkRes) && !stmt.getSubject().equals(mainRes)) {
	                 		returnModel.add(recursiveSanitizeBuild(stmt.getSubject(), mainRes));
	                 	}
	          		}
			 }
			 
			 return returnModel;
		 }
		 
		 
		/**
		* Executes a pair scoring method, utilizing the matchAttribute. This attribute is expected to 
		* return 2 to n results from the given query. This "pair" will then be utilized as a matching scheme 
		* to construct a sub dataset. This dataset can be scored and stored as a match 
		* @param  attribute an attribute to perform the matching query
		* @return score model
		*/
		@SuppressWarnings("unused")
		private Model pairwiseScore(String attribute) {			
		 	//iterate thru scoringInput pairs against matched pairs
		 	//TODO Nicholas: support partial scoring, multiples matches against several pairs
		 	//if pairs match, store publication to matched author in Model
			
			ResultSet scoreInputResult;
			String matchQuery = "PREFIX score: <http://vivoweb.org/ontology/score#> " +
								"SELECT ?x ?" + attribute + " " + 
								"WHERE { ?x score:" + attribute + " ?" + attribute + "}";
			String coreAttribute = "core:" + attribute;
			
			
			//Exact Match
			log.info("Executing pairWise for " + attribute);
			log.debug(matchQuery);
			scoreInputResult = executeQuery(this.scoreInput, matchQuery);
			
			//Log extra info message if none found
			if (!scoreInputResult.hasNext()) {
				log.info("No matches found for " + attribute + " in input");
			} else {
				log.info("Looping thru matching" + attribute + " from input");
			}
			
			//look for exact match in vivo
			while (scoreInputResult.hasNext()) {
			 	//create pairs of *attribute* from matched
		    	log.info("Creating pairs of " + attribute + " from input");
            }	    			 
	    
	    	return this.scoreOutput;
		 }
		 
		 
		 /**
		 * Executes an exact matching algorithm for author disambiguation
		 * @param  attribute an attribute to perform the exact match
		 * @return model of matched statements
		 */
		 private Model exactMatch(String attribute) {
				String scoreMatch;
				String queryString;
				Resource paperResource;
				RDFNode matchNode;
				RDFNode paperNode;
				ResultSet vivoResult;
				QuerySolution scoreSolution;
			 	ResultSet scoreInputResult;
			 	
			 	String matchQuery = "PREFIX score: <http://vivoweb.org/ontology/score#> " +
		    						"SELECT ?x ?" + attribute + " " + 
		    						"WHERE { ?x score:" + attribute + " ?" + attribute + "}";
			 	String coreAttribute = "core:" + attribute;


			 	//Exact Match
			 	log.info("Executing exactMatch for " + attribute);
			 	log.debug(matchQuery);
		 		scoreInputResult = executeQuery(this.scoreInput, matchQuery);
		 		
		    	//Log extra info message if none found
		    	if (!scoreInputResult.hasNext()) {
		    		log.info("No matches found for " + attribute + " in input");
		    	} else {
		    		log.info("Looping thru matching" + attribute + " from input");
		    	}
		    	
		    	//look for exact match in vivo
		    	while (scoreInputResult.hasNext()) {
		    		scoreSolution = scoreInputResult.nextSolution();
	                matchNode = scoreSolution.get(attribute);
	                paperNode = scoreSolution.get("x");
	                paperResource = scoreSolution.getResource("x");
	                
	                scoreMatch = matchNode.toString();
	                
	                log.info("\nChecking for " + scoreMatch + " from " + paperNode.toString() + " in VIVO");
	    			
	                //Select all matching attributes from vivo store
	    			queryString =
						"PREFIX core: <http://vivoweb.org/ontology/core#> " +
						"SELECT ?x " +
						"WHERE { ?x " + coreAttribute + " \"" +  scoreMatch + "\" }";
	    			
	    			log.debug(queryString);
	    			
	    			vivoResult = executeQuery(this.vivo, queryString);
	    			
	    			commitResultSet(this.scoreOutput,vivoResult,paperResource,matchNode,paperNode);
	            }	    			 
		    	
		    	return this.scoreOutput;
		 }
	}
