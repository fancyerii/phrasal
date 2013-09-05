package edu.stanford.nlp.mt.tools;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.PriorityQueue;

import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.KenLanguageModel;
import edu.stanford.nlp.mt.base.LineIndexedCorpus;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.base.SimpleSequence;

/**
 * Moore-Lewis's "Intelligent Selection of Language Model Training Data" (ACL2010).
 * 
 * @author Thang Luong <lmthang@stanford.edu>, 2013
 *
 */
public class MooreLewisCorpusSelection {
  private KenLanguageModel inKenLM;
  private KenLanguageModel outKenLM;
  private LineIndexedCorpus data;
  private double[] crossEntDiffScores; // cross-entropy diff scores
  
  private IString startToken;
  private IString endToken;
  private int order;
  
  static public void usage() {
    System.err.println("Usage:\n\tjava ...MooreLewisCorpusSelection " +
        "(selectionSize) (inDomainKenLM) (outDomainKenLM) (data) (outPrefix)");
    System.err.println("  outPrefix: for each selectSize we will output three files outPrefix.data, "
        + "outPrefix.score (cross-entropy diff scores), and outPrefix.line (0-based line indices)");
  }

  public MooreLewisCorpusSelection(String inDomainKenLMFile, String outDomainKenLMFile, String dataFile){
    // in-domain KenLM
    System.err.println("# Loading in-domain KenLM " + inDomainKenLMFile);
    inKenLM = new KenLanguageModel(inDomainKenLMFile);

    // out-domain KenLM
    System.err.println("# Loading out-domain KenLM " + outDomainKenLMFile);
    outKenLM = new KenLanguageModel(outDomainKenLMFile);

    // data file
    System.err.println("# Opening " + dataFile);
    try {
      data = new LineIndexedCorpus(dataFile);
    } catch (IOException e) {
      System.err.println("! Can't load data file " + dataFile);
      e.printStackTrace();
    }
  
    // cross-entropy diff scores
    crossEntDiffScores = new double[data.size()];
    
    // others
    startToken = inKenLM.getStartToken();
    endToken = inKenLM.getEndToken();
    order = inKenLM.order();
    if(!startToken.equals(outKenLM.getStartToken()) || !endToken.equals(outKenLM.getEndToken()) 
        || order != outKenLM.order()){
      System.err.println("mismatch in either startToken, endToken, or order between in-domain and out-domain KenLMs");
      System.exit(1);
    }
  }

  // smallest values first
  class SentenceScoreComparator implements Comparator<Integer> {
    @Override
    public int compare(Integer o1, Integer o2) {         
       return (int)Math.signum(crossEntDiffScores[o1]-crossEntDiffScores[o2]);
    }      
  }
  
  public double computeCrossEntDiff(String line){
    // build sequence of IString
    String[] tokens = line.split("\\s+");
    IString[] istrings = new IString[tokens.length+2];
    istrings[0] = startToken; // start token
    for (int i = 0; i < tokens.length; i++) {
      istrings[i+1] = new IString(tokens[i]);
    }
    istrings[istrings.length-1] = endToken; // end token
    Sequence<IString> sequence = new SimpleSequence<IString>(istrings);

    // compute entropy diff = -1/N*log p_in - (-1/N*log p_out) 
    int numNgrams = (istrings.length<order)?1 : (istrings.length-order+1); // N
    return -inKenLM.score(sequence)/numNgrams + outKenLM.score(sequence)/numNgrams;
  }
  
  public void select(String outPrefix, int selectionSize) throws IOException {
    PriorityQueue<Integer> Q = new PriorityQueue<Integer>(crossEntDiffScores.length, new SentenceScoreComparator());
    int count=0;
    for (String line : data) {
      crossEntDiffScores[count] = computeCrossEntDiff(line);
      Q.add(count);
      
      if(++count % 100000 == 0){
        System.err.print(" (" + count/1000 + "K) ");
      }
    }    
    System.err.println("Done! Num lines = " + count);

    // init print writers
    System.err.println("# Output sorted cross-entropy diff scores ...");
    PrintWriter selectedDataPW = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(outPrefix + ".data"), "UTF-8"));  
    PrintWriter selectedScorePW = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(outPrefix + ".score"), "UTF-8"));  
    PrintWriter selectedLinePW = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(outPrefix + ".line"), "UTF-8"));

    // picking up smallest cross-entropy diff values first
    count = 0;
    while(!Q.isEmpty()){
      int lineId = Q.poll();
      selectedDataPW.println(data.get(lineId));
      selectedScorePW.println(crossEntDiffScores[lineId]);
      selectedLinePW.println(lineId); // 0-based index
      
      count++;
      
      if(count==selectionSize){
        break;
      }
    }
    
    selectedDataPW.close();
    selectedScorePW.close();
    selectedLinePW.close();
  }
  
  public static void main(String[] args) throws IOException {
    if (args.length != 5) {
      System.err.print("Input arguments (count=" + args.length + "):");
      for (String string : args) { System.err.print(" " + string); }
      System.err.println();
      usage();
      System.exit(-1);
    }

    int selectionSize = Integer.parseInt(args[0]);
    String inDomainKenLMFile = args[1]; // in-domain
    String outDomainKenLMFile = args[2]; // out-domain
    String dataFile = args[3];
    String outPrefix = args[4];
    
    MooreLewisCorpusSelection mlcs = new MooreLewisCorpusSelection(inDomainKenLMFile, outDomainKenLMFile, dataFile);
    
    // MooreLewis selection
    mlcs.select(outPrefix, selectionSize);
  }
}