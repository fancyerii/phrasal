package edu.stanford.nlp.mt.preordering;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;

import edu.berkeley.nlp.util.CollectionUtils;
import edu.stanford.nlp.classify.Dataset;
import edu.stanford.nlp.classify.LinearClassifier;
import edu.stanford.nlp.classify.LinearClassifierFactory;
import edu.stanford.nlp.ling.BasicDatum;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.Label;
import edu.stanford.nlp.ling.RVFDatum;
import edu.stanford.nlp.movetrees.TreeUtils;
import edu.stanford.nlp.mt.base.CoreNLPCache;
import edu.stanford.nlp.mt.base.IString;
import edu.stanford.nlp.mt.base.Sequence;
import edu.stanford.nlp.mt.process.Preprocessor;
import edu.stanford.nlp.mt.train.SymmetricalWordAlignment;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.semgraph.SemanticGraphFactory;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.trees.EnglishGrammaticalStructureFactory;
import edu.stanford.nlp.trees.LabeledScoredTreeFactory;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Filter;
import edu.stanford.nlp.util.Generics;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.PropertiesUtils;
import edu.stanford.nlp.util.StringUtils;

/**
 * Performs preordering of the source sentences based on 
 * their dependency tree.
 * 
 * Implements the method described in 
 *  Lerner, Uri, and Slav Petrov. 
 * "Source-Side Classifier Preordering for Machine Translation." 
 * Proceedings of EMNLP. 2013.
 * 
 * @author Sebastian Schuster
 */

public class DependencyTreeBasedPreorderer implements Preprocessor {


  private static HashMap<Integer, LinearClassifier<String, String>> classifiers = new HashMap<Integer, LinearClassifier<String, String>>();
  private static HashMap<String, String> universalPOSMapping = new HashMap<String, String>();
  private static int parseFileIndex = 0;
  private static int parseSentenceIndex = 0;
  private static LabeledScoredTreeFactory tf = new LabeledScoredTreeFactory();
  
  /* Only the K most frequent permutation classes will be used to train the classifier. */
  private static int K = 20;

  /* Discard features wit a count of less than FEATURE_THRESHOLD */
  private static int FEATURE_THRESHOLD = 5;
  
  /*
   * Mapping from Penn treebank tags to Universal POS Tags as described in
   * "A Universal Part-of-Speech Tagset" by Slav Petrov, Dipanjan Das and Ryan McDonald
   */
  static {
    universalPOSMapping.put("!", ".");
    universalPOSMapping.put("#", ".");
    universalPOSMapping.put("$", ".");
    universalPOSMapping.put("''", ".");
    universalPOSMapping.put("(", ".");
    universalPOSMapping.put(")", ".");
    universalPOSMapping.put(",", ".");
    universalPOSMapping.put("-LRB-", ".");
    universalPOSMapping.put("-RRB-", ".");
    universalPOSMapping.put(".", ".");
    universalPOSMapping.put(",", ".");
    universalPOSMapping.put("\"", ".");
    universalPOSMapping.put(":", ".");
    universalPOSMapping.put("?", ".");
    universalPOSMapping.put("CC", "CONJ");
    universalPOSMapping.put("CD", "NUM");
    universalPOSMapping.put("CD|RB", "X");
    universalPOSMapping.put("DT", "DET");
    universalPOSMapping.put("EX", "DET");
    universalPOSMapping.put("FW", "X");
    universalPOSMapping.put("IN", "ADP");
    universalPOSMapping.put("IN|RP", "ADP");
    universalPOSMapping.put("JJ", "ADJ");
    universalPOSMapping.put("JJR", "ADJ");
    universalPOSMapping.put("JJRJR", "ADJ");
    universalPOSMapping.put("JJS", "ADJ");
    universalPOSMapping.put("JJ|RB", "ADJ");
    universalPOSMapping.put("JJ|VBG", "ADJ");
    universalPOSMapping.put("LS", "X");
    universalPOSMapping.put("MD", "VERB");
    universalPOSMapping.put("NN", "NOUN");
    universalPOSMapping.put("NNP", "NOUN");
    universalPOSMapping.put("NNPS", "NOUN");
    universalPOSMapping.put("NNS", "NOUN");
    universalPOSMapping.put("NN|NNS", "NOUN");
    universalPOSMapping.put("NN|SYM", "NOUN");
    universalPOSMapping.put("NN|VBG", "NOUN");
    universalPOSMapping.put("NP", "NOUN");
    universalPOSMapping.put("PDT", "DET");
    universalPOSMapping.put("POS", "PRT");
    universalPOSMapping.put("PRP", "PRON");
    universalPOSMapping.put("PRP$", "PRON");
    universalPOSMapping.put("PRP|VBP", "PRON");
    universalPOSMapping.put("PRT", "PRT");
    universalPOSMapping.put("RB", "ADV");
    universalPOSMapping.put("RBR", "ADV");
    universalPOSMapping.put("RBS", "ADV");
    universalPOSMapping.put("RB|RP", "ADV");
    universalPOSMapping.put("RB|VBG", "ADV");
    universalPOSMapping.put("RN", "X");
    universalPOSMapping.put("RP", "PRT");
    universalPOSMapping.put("SYM", "X");
    universalPOSMapping.put("TO", "PRT");
    universalPOSMapping.put("UH", "X");
    universalPOSMapping.put("VB", "VERB");
    universalPOSMapping.put("VBD", "VERB");
    universalPOSMapping.put("VBD|VBN", "VERB");
    universalPOSMapping.put("VBG", "VERB");
    universalPOSMapping.put("VBG|NN", "VERB");
    universalPOSMapping.put("VBN", "VERB");
    universalPOSMapping.put("VBP", "VERB");
    universalPOSMapping.put("VBP|TO", "VERB");
    universalPOSMapping.put("VBZ", "VERB");
    universalPOSMapping.put("VP", "VERB");
    universalPOSMapping.put("WDT", "DET");
    universalPOSMapping.put("WH", "X");
    universalPOSMapping.put("WP", "PRON");
    universalPOSMapping.put("WP$", "PRON");
    universalPOSMapping.put("WRB", "ADV");
    universalPOSMapping.put("``", ".");
  }
  
  
  /**
	 * adds the following features for a word prefixed by prefix:
	 *  - the word identity
	 *  - its POS tag
	 */
  public static void addWordFeature(List<String> features, CoreLabel w, String prefix) {
    features.add(prefix + "-WORD:" + w.word());
    features.add(prefix + "-POS:" + w.tag());
    String universalPOS = universalPOSMapping.get(w.tag());
    if (universalPOS == null)
      universalPOS = ".";
    features.add(prefix + "-UPOS:" + universalPOS);
  }
  
  /**
   * Extracts features from a gap between first and last
   */
  public static void addGapFeatures(List<String> features, List<CoreLabel> tokens, CoreLabel first, CoreLabel last, String prefix) {
    addWordFeature(features, tokens.get(first.index()), prefix + "-GAP-FIRST");
    addWordFeature(features, tokens.get(last.index() - 2), prefix + "-GAP-LAST");
  }
  
 /**
  * Extracts the features from a family headed by headWord
  */
  public static List<String> extractFeatures(Family family, CoreMap currentSentence) {
    IndexedWord headWord = family.getHeadWord();
    List<CoreLabel> tokens = currentSentence.get(CoreAnnotations.TokensAnnotation.class);    
    
    List<String> features = new ArrayList<String>();
    if (family.isQuestion())
      features.add("ISQUESTION");
    
    //the head word
    addWordFeature(features, headWord, "HEAD");
    features.add("HEAD-DEP:" + headWord.category());
    //the children
    int i = 0;
    IndexedWord prevChild = null;
    for (IndexedWord child : family.getSortedWords()) {
      if (child == headWord) {
        prevChild = null;
        continue;
      }
      
      addWordFeature(features, child, "CHILD-" + i);
      addWordFeature(features, child, child.category() + "-CHILD-" + i);
      features.add("CHILD-" + i + "-DEP:" + child.category());

      if (child.index() < headWord.index()) {
        if (child.index() == headWord.index() - 1)
          addWordFeature(features, child, "CHILD-" + i + "-PREVWORD");
        else {
          addWordFeature(features, child, "CHILD-" + i + "-BEFORE");
          //first and last word of gap between child and head
          addGapFeatures(features, tokens, child, headWord, "CHILD-" + i);
        }
      }
      if (child.index() > headWord.index()) {
        if (child.index() == headWord.index() + 1)
          addWordFeature(features, child, "CHILD-" + i + "-NEXTWORD");
        else {
          addWordFeature(features, child, "CHILD-" + i + "-AFTER");
          //first and last word of gap between child and head
          addGapFeatures(features, tokens, headWord, child, "CHILD-" + i);
        }
      }
      if (prevChild != null && prevChild.index() != child.index() - 1)
        addGapFeatures(features, tokens, prevChild, child, "CHILD-" + i);
      i++;
      prevChild = child;
    }
    
    //the immediate siblings of the head word
    if (headWord.index() > 1)
      addWordFeature(features, tokens.get(headWord.index() - 2), "SIBLING-LEFT");
    else 
      features.add("SIBLING-LEFT-<NULL>"); //no sibling to the left
    if (headWord.index() < tokens.size())
      addWordFeature(features, tokens.get(headWord.index()), "SIBLING-RIGHT");
    else
      features.add("SIBLING-RIGHT-<NULL>"); //no sibling to the right
    return features;
  }

  public static boolean alignmentConstraintsSatisfied(ArrayList<IndexedWord> family, SymmetricalWordAlignment alignment) {
    int firstWordIndex = family.get(0).index();
    int lastWordIndex = family.get(family.size() - 1).index();
    //check if every source word is aligned to a target word and 
    //every target word is aligned to only one source word
    for (IndexedWord w : family) {
      SortedSet<Integer> targetIndices = alignment.f2e(w.index() - 1);
      if (targetIndices.isEmpty())
        return false;
      //check that if a source word is aligned to multiple target words
      //that no other target word in that range is aligned to a source 
      //word within the family
      for (Integer t : targetIndices) {
        if (alignment.e2f(t).size() > 1)
          return false;
      }
      if (targetIndices.size() > 1) {
        Integer minIndex = targetIndices.first();
        Integer maxIndex = targetIndices.last();
        for (int i = minIndex + 1; i < maxIndex; i++) {
          if (!targetIndices.contains(i)) {
            for (Integer s : alignment.e2f(i)) {
              if (s >= firstWordIndex && s <= lastWordIndex)
                return false;
            }
          }
        }
      }
    }
    return true;
  }

  /**
   * Extracts the permutation class of a given family and the alignment of the sentence
   * the family was extracted from. 
   * 
   * E.g. given the sentence
   * The black cat climbed to the tree top.
   * 
   * and the alignment
   * The black cat climbed to the tree top.
   * The black cat the tree top climbed.
   * 
   * and its extracted family
   * cat, climbed, to
   * 
   * then the permutation class would be
   * 0-2-1
   * (cat, to, climbed)
   */
  public static String extractPermutationClass(ArrayList<IndexedWord> family, SymmetricalWordAlignment alignment) {
     ArrayList<Pair<Integer,Integer>> alignmentPairs = new ArrayList<Pair<Integer,Integer>>();
     int familySize = family.size();
     for (int i = 0; i < familySize; i++) {
       IndexedWord w = family.get(i);
       Integer targetIndex = alignment.f2e(w.index()-1).first();
       Pair<Integer, Integer> alignmentPair = new Pair<Integer,Integer>(targetIndex, i);
       alignmentPairs.add(alignmentPair);
     }
     alignmentPairs = (ArrayList<Pair<Integer,Integer>>) CollectionUtils.sort(alignmentPairs);
     StringBuilder sb = new StringBuilder();
     boolean first = true;
     for (Pair<Integer, Integer> p : alignmentPairs) {
       if (!first) 
         sb.append("-");
       else
         first = false;
       sb.append(p.second());
     }
     return sb.toString();
   }
  
 /**
  * Returns all dependency families, i.e. an ordered list of a head and its immediate children.
  * If an alignment is passed then only families that fulfill the alignment constraints will
  * be added to the list.   
  */
 public static List<Family> extractFamilies(CoreMap currentSentence, SymmetricalWordAlignment alignment)  {
   LinkedList<IndexedWord> q = new LinkedList<IndexedWord>();
   ArrayList<Family> families = new ArrayList<Family>();
   
   Tree parseTree = currentSentence.get(TreeCoreAnnotations.TreeAnnotation.class);
   String docID = currentSentence.get(CoreAnnotations.DocIDAnnotation.class);
   Integer sentenceIndex = currentSentence.get(CoreAnnotations.SentenceIndexAnnotation.class);
   EnglishGrammaticalStructureFactory gsf = new EnglishGrammaticalStructureFactory(new NoFilter());
   SemanticGraph basicDependencies = SemanticGraphFactory.generateUncollapsedDependencies(gsf.newGrammaticalStructure(parseTree), docID, sentenceIndex);

   boolean isQuestion = false;
   if (parseTree != null && parseTree.firstChild() != null && parseTree.firstChild().label().toString().endsWith("Q"))
     isQuestion = true;
       
   IndexedWord sentenceHead = basicDependencies.getRoots().iterator().next();

   q.add(sentenceHead);
   while (!q.isEmpty()) {
     IndexedWord currentHead = q.removeFirst();
     ArrayList<IndexedWord> familyWords = new ArrayList<IndexedWord>(); 
     for (SemanticGraphEdge edge : basicDependencies.outgoingEdgeIterable(currentHead)) {
       IndexedWord dependent = edge.getDependent();
       dependent.setCategory(edge.getRelation().getShortName());
       q.add(dependent);
       familyWords.add(dependent);
     }
     if (!familyWords.isEmpty()) {
       familyWords.add(currentHead);
       ArrayList<IndexedWord> sortedFamilyWords = (ArrayList<IndexedWord>) CollectionUtils.sort(familyWords);
       
       //Debug:
       //System.err.print("Family: ");

       //for (IndexedWord w : sortedFamilyWords) {
       //  System.err.print(w);
       //  System.err.print("-");
       //  System.err.print(w.index());
       //  System.err.print(" ");
       //}
       //System.err.println();
       
       boolean include = true;
       if (alignment != null) 
         include = alignmentConstraintsSatisfied(sortedFamilyWords, alignment);
       if (include) {
         Family family = new Family(currentHead, sortedFamilyWords, isQuestion); 
         families.add(family);
       }
     }
   }
   return families;
 }
  
  
 /**
  * Extract training examples from a dependency parsed sentence and its alignment.
  * For each head in the sentence we generate a family if the alignment 
  * constraints for the words in the family are satisfied.
  */
  
  public static void extractTrainingExamples(HashMap<Integer, Dataset<String, String>> datasets, CoreMap currentSentence, SymmetricalWordAlignment alignment) {

    List<Family> families = extractFamilies(currentSentence, alignment);
    for (Family f : families) {
      String permutationClass = extractPermutationClass(f.getSortedWords(), alignment);
      List<String> features = extractFeatures(f, currentSentence);
      Integer familySize = f.getSize();
      if (!datasets.containsKey(familySize)) 
        datasets.put(familySize, new Dataset<String, String>());
      datasets.get(familySize).add(features, permutationClass);  
    }
  }
  
  public static Dataset<String, String> pruneDataset(Dataset<String, String> ds) {
    if (ds.numClasses() <= K)
      return ds;
    Dataset<String, String> prunedDs = new Dataset<String, String>();
    ClassicCounter<String> numDatumsPerLabel = ds.numDatumsPerLabel();
    Counters.retainTop(numDatumsPerLabel, K);
    Iterator<RVFDatum<String, String>> it = ds.iterator();
    while (it.hasNext()) {
      RVFDatum<String, String> d = it.next();
      if (numDatumsPerLabel.containsKey(d.label()))
        prunedDs.add(d);
    }
    return prunedDs;
  }
  
  
  /**
   * Trains the model
   */
  
  public static void train(HashMap<Integer, Dataset<String, String>> datasets) {
    for (Integer size : datasets.keySet()) {
      LinearClassifierFactory<String, String> lcf = new LinearClassifierFactory<String, String>();
      Dataset<String, String> ds = datasets.get(size);
      Dataset<String, String> prunedDs = pruneDataset(ds);
      ds = null;
      prunedDs.applyFeatureCountThreshold(FEATURE_THRESHOLD);
      LinearClassifier<String, String> lc = lcf.trainClassifier(prunedDs);
      classifiers.put(size, lc);
    }
  }
  
  
  
  /**
   * Applies a permutation to a dependency family
   */

  public static void reorderFamily(List<IndexedWord> indexedTokens, Family f, String permutationClass) {
    int familySize = f.getSize() ;
    String[] indices = permutationClass.split("-");
    int[] tokenIndices = new int[familySize + 1];
    for (int i = 0; i < familySize + 1; i++) {
      int idx = Integer.parseInt(indices[i]);
      tokenIndices[idx] = f.getSortedWords().get(i).index();
    }
    for (int i = 0; i < familySize + 1; i++) {
      indexedTokens.get(f.getSortedWords().get(i).index() - 1).setIndex(tokenIndices[i]);
    }
  }
  
  private static void reorderFamily(Tree headNode, Family f, String permutationClass) {
    int familySize = f.getSize() ;
    String[] indices = permutationClass.split("-");
    List<Tree> currentChildren = headNode.getChildrenAsList();
    ArrayList<Tree> reorderedChildren = new ArrayList<Tree>(currentChildren);
    for (int i = 0; i < familySize + 1; i++) {
      int idx = Integer.parseInt(indices[i]);
      reorderedChildren.set(idx, currentChildren.get(i));
    }
    headNode.setChildren(reorderedChildren);
  }
  
  /**
   * Performs the preordering on an dependency annotated sentence
   */
  public static String reorder(CoreMap currentSentence, boolean outputPermutations) {
    List<CoreLabel> tokens = currentSentence.get(CoreAnnotations.TokensAnnotation.class);
    try {
      List<IndexedWord> indexedTokens = new ArrayList<IndexedWord>();
      int idx = 1;
      for (CoreLabel t: tokens) {
        IndexedWord iw = new IndexedWord(t);
        iw.setIndex(idx);
        indexedTokens.add(iw);
        idx++;
      }
      
      Tree preorderedTree = null;
      HashMap<IndexedWord, Tree> treeNodes = new HashMap<IndexedWord, Tree>();
      List<Family> families = extractFamilies(currentSentence, null);
      for (Family f : families) {
        if (preorderedTree == null) {
          preorderedTree = tf.newLeaf(f.getHeadWord());
          treeNodes.put(f.getHeadWord(), preorderedTree);
        } 
        Tree headNode = treeNodes.get(f.getHeadWord());

        for (IndexedWord t : f.getSortedWords()) {
          Tree childNode = tf.newLeaf(t);
          headNode.addChild(childNode);
          if (t != f.getHeadWord())
            treeNodes.put(t, childNode);
        }
        List<String> features = extractFeatures(f, currentSentence);
        Datum<String, String> d = new BasicDatum<String, String>(features);
        if (classifiers.containsKey(f.getSize())) {
          //System.err.println("Family: " + f);
          String permutationClass = classifiers.get(f.getSize()).classOf(d);
          //System.err.println("Permutation Class: " + permutationClass);
          reorderFamily(headNode, f, permutationClass);
        }
      }
      //for (CoreLabel t : tokens) {
      //  System.out.print(t.word() + "-" + t.index() + " ");
      //}
      //System.out.println("");
      //for (IndexedWord t : indexedTokens) {
      //  System.out.print(t.word() + "-" + t.index() + " ");
      //}
      //System.out.println("");
      
      //handle single word sentences
      if (preorderedTree == null)
        return outputPermutations ? Integer.toString(tokens.get(0).index() - 1) : tokens.get(0).word();
      
      
      List<Tree> reorderedTokens = preorderedTree.getLeaves();
 
      StringBuilder sb = new StringBuilder();
      for (Tree t : reorderedTokens) {
        if (outputPermutations) {
          CoreLabel iw =  (CoreLabel) t.label();
          sb.append(iw.index() - 1).append(" ");
        } else {
          sb.append(t.label().value()).append(" ");
        }
      }
      return sb.toString();
    } catch (Exception e) {
      e.printStackTrace();
      StringBuilder sb = new StringBuilder();
      for (CoreLabel t : tokens) {
        if (outputPermutations)
          sb.append(t.index()-1).append(" ");
        else
          sb.append(t.word()).append(" ");
      }
      return sb.toString();
    } 
  }
  


  /**
   * Loads the model from disk.
   */
  
  @SuppressWarnings("unchecked")
  private static void loadModel(String path) throws IOException, ClassNotFoundException {
    FileInputStream fis = new FileInputStream(path);
    ObjectInputStream ois = new ObjectInputStream (fis);
    Object obj = ois.readObject();
    if (obj instanceof HashMap<?,?>) {
      classifiers = (HashMap<Integer, LinearClassifier<String, String>>) obj;
    }
    System.err.println("Loaded model!");
    ois.close();
    fis.close();
  }
  
  /**
   * Saves the model to disk.
   */
  private static void saveModel(String path) throws IOException {
    FileOutputStream fos = new FileOutputStream(path);
    ObjectOutputStream oos = new ObjectOutputStream(fos);
    oos.writeObject(classifiers);
    oos.close();
    fos.close();
  }
  
  
  /**
   * Command-line option specification.
   */
  private static Map<String,Integer> optionArgDefs() {
    Map<String,Integer> optionArgDefs = Generics.newHashMap();
    optionArgDefs.put("train", 0); 
    optionArgDefs.put("trainAnnotationsSplit", 0); 
    optionArgDefs.put("trainSourceTokens", 1); 
    optionArgDefs.put("trainTargetTokens", 1); 
    optionArgDefs.put("trainAlignment", 1);
    optionArgDefs.put("trainAnnotations", 1);
    optionArgDefs.put("modelPath", 1);
    optionArgDefs.put("annotations", 1); 
    optionArgDefs.put("annotationsSplit", 0); 
    optionArgDefs.put("permutations", 0); 


    return optionArgDefs;
  }
  
  public static CoreMap getParsedSentence(String filename, int index, boolean isSplit) {
    if (!isSplit) {
      if (!CoreNLPCache.isLoaded()) {
        CoreNLPCache.loadSerialized(filename);
      }
      return CoreNLPCache.get(index);
    } else {
      if (!CoreNLPCache.isLoaded()) {
        parseFileIndex = 0;
        parseSentenceIndex = 0;
        String composedFilename = filename + "." + parseFileIndex;
        CoreNLPCache.loadSerialized(composedFilename);
      }
      CoreMap c = CoreNLPCache.get(index - parseSentenceIndex);
      if (c == null) {
        parseFileIndex++;
        String composedFilename = filename + "." + parseFileIndex;
        File file = new File(composedFilename);
        if (file.exists()) {
          parseSentenceIndex = index;
          CoreNLPCache.loadSerialized(composedFilename);
          c = CoreNLPCache.get(index - parseSentenceIndex);
        }
      }
      return c;
    }
  }
  

  public static void main(String[] args) throws ClassNotFoundException, IOException {
    Properties options = StringUtils.argsToProperties(args, optionArgDefs());
    boolean train = PropertiesUtils.getBool(options, "train", false);

    if (train) {
      String trainSourceTokens = PropertiesUtils.get(options, "trainSourceTokens", null, String.class);
      String trainTargetTokens = PropertiesUtils.get(options, "trainTargetTokens", null, String.class);
      String trainAlignment = PropertiesUtils.get(options, "trainAlignment", null, String.class);
      String trainAnnotations = PropertiesUtils.get(options, "trainAnnotations", null, String.class);
      boolean trainAnnotationsSplit = PropertiesUtils.getBool(options, "trainAnnotationsSplit", false);
      File sourceSentences = new File(trainSourceTokens);
      File targetSentences = new File(trainTargetTokens);
      File alignmentFile = new File(trainAlignment);
      HashMap<Integer, Dataset<String, String>> datasets = new HashMap<Integer, Dataset<String, String>>();
      BufferedReader sourceReader = new BufferedReader(new FileReader(sourceSentences));
      BufferedReader targetReader = new BufferedReader(new FileReader(targetSentences));
      BufferedReader alignmentReader = new BufferedReader(new FileReader(alignmentFile));
      String sourceSentence;
      int i = 0;
      while ((sourceSentence = sourceReader.readLine()) != null) {
        try {
          CoreMap sentence = getParsedSentence(trainAnnotations, i, trainAnnotationsSplit);
          String targetSentence = targetReader.readLine();
          String alignmentString = alignmentReader.readLine();
          //System.err.println("---------------------------");
          //System.err.println(sourceSentence);
          //System.err.println(targetSentence);
          //System.err.println(alignmentString);
          SymmetricalWordAlignment alignment = new SymmetricalWordAlignment(sourceSentence, targetSentence, alignmentString);
          extractTrainingExamples(datasets, sentence, alignment);
        } catch (Exception e) {
          e.printStackTrace();
        }
        i++;
      }
      train(datasets);      
      sourceReader.close();
      targetReader.close();
      alignmentReader.close();
      
      String modelPath = PropertiesUtils.get(options, "modelPath", null, String.class);
      if (modelPath != null)
        saveModel(modelPath);  
      CoreNLPCache.flush();
    } else {
      String modelPath = PropertiesUtils.get(options, "modelPath", null, String.class);
      loadModel(modelPath);
    }
    String annotations = PropertiesUtils.get(options, "annotations", null, String.class);
    if (annotations == null)
      return;
    boolean annotationsSplit = PropertiesUtils.getBool(options, "annotationsSplit", false);
    boolean outputPermutations = PropertiesUtils.getBool(options, "permutations", false);

    int i = 0;
    CoreMap sentence = null;
    while ((sentence = getParsedSentence(annotations, i, annotationsSplit)) != null 
        || getParsedSentence(annotations, i+1, annotationsSplit) != null) {
        
      //for (CoreLabel t: sentence.get(CoreAnnotations.TokensAnnotation.class)) {
      //  System.out.print(t.word() + " ");
      //}
      //System.out.print("\n");
      //System.err.print("Reordered sentence #");
      //System.err.println(i);
      
      //In case of an empty line sentence will be null.
      if (sentence != null)
        System.out.println(reorder(sentence, outputPermutations));
      else 
        System.out.println("");
      i++;
    }
  }

  @Override
  public SymmetricalWordAlignment processAndAlign(String input) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public Sequence<IString> process(String input) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public String toUncased(String input) {
    // TODO Auto-generated method stub
    return null;
  }
  
  private static class Family {
    private IndexedWord headWord;
    private ArrayList<IndexedWord> sortedWords;
    private boolean isQuestion;
    
    
    public Family(IndexedWord headWord, ArrayList<IndexedWord> sortedWords, boolean isQuestion) {
      this.headWord = headWord;
      this.sortedWords = sortedWords;
      this.isQuestion = isQuestion;
    }
    
    public ArrayList<IndexedWord> getSortedWords() {
      return sortedWords;
    }
    
    public IndexedWord getHeadWord() {
      return headWord;
    }
    
    public int getSize() {
      return sortedWords.size() - 1;
    }
    
    public boolean isQuestion() {
      return isQuestion;
    }
    
    public String toString() {
      StringBuilder sb = new StringBuilder();
      sb.append("Family: ");
      for (IndexedWord w : getSortedWords()) {
        sb.append(w).append(" ");
      }
      return sb.toString();
    }
  }

  
  
  private static class NoFilter implements Filter<String> {

    private static final long serialVersionUID = 8052532728769677017L;

    @Override
    public boolean accept(String obj) {
      return true;
    }
    
  }
  
}