package mt.tune;

import java.io.*;
import java.util.*;

import mt.base.*;
import mt.decoder.util.*;
import mt.metrics.*;
import mt.metrics.ter.TERcalc;

import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.util.ErasureUtils;

/**
 * Minimum Error Rate Training (MERT).
 *
 * Optimization for non smooth error surfaces.
 *
 * @author danielcer
 */
public class UnsmoothedMERT extends Thread {

  private static boolean tokenizeNIST = false;

  static boolean smoothBLEU = System.getProperty("smoothBLEU") != null;

  static final String GENERATIVE_FEATURES_LIST_RESOURCE = "mt/resources/generative.features";
  static final Set<String> generativeFeatures = SSVMScorer
          .readGenerativeFeatureList(GENERATIVE_FEATURES_LIST_RESOURCE);

	public static final String DEBUG_PROPERTY = "UnsmoothedMERTDebug";
	public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));

  static final double DEFAULT_C = 100;
  static double C = DEFAULT_C;
  static final double DEFAULT_T = 1;
  static double T = DEFAULT_T;
  static final double DEFAULT_UNSCALED_L_RATE = 0.1;
  static double lrate = DEFAULT_UNSCALED_L_RATE;
  static final double MIN_OBJECTIVE_CHANGE_SGD = 1e-5;
  static final int NO_PROGRESS_LIMIT = 20;
  static final double NO_PROGRESS_MCMC_TIGHT_DIFF = 1e-6;
  static final double NO_PROGRESS_MCMC_COSINE = 0.95;
  static final int MCMC_BATCH_SAMPLES = 10;
  static final int MCMC_MIN_BATCHES = 0;
  static final int MCMC_MAX_BATCHES = 20;
  static final int MCMC_MAX_BATCHES_TIGHT = 50;

  static final double NO_PROGRESS_SSD = 1e-6;

  static final double MAX_LOCAL_ALL_GAP_WTS_REUSE = 0.035;

  static public double MIN_PLATEAU_DIFF = 0.0;
  static public final double MIN_OBJECTIVE_DIFF = 1e-5;

  public static final int DEFAULT_TER_BEAM_WIDTH = 5; // almost as good as 20
  public static final int DEFAULT_TER_SHIFT_DIST = 12; // Yaser suggested 10; I set it to 2*dlimit = 12

  private static long SEED = 8682522807148012L;
  private static Random globalRandom;

  public double mcmcTightExpectedEval(MosesNBestList nbest, Counter<String> wts, EvaluationMetric<IString,String> emetric) {
    return mcmcTightExpectedEval(nbest, wts, emetric, true);
  }

  static boolean alwaysSkipMCMC = true;
  public double mcmcTightExpectedEval(MosesNBestList nbest, Counter<String> wts, EvaluationMetric<IString,String> emetric, boolean regularize) {
		if (alwaysSkipMCMC) return 0;
    System.err.printf("TMCMC weights:\n%s\n\n", Counters.toString(wts, 35));

    // for quick mixing, get current classifier argmax
    List<ScoredFeaturizedTranslation<IString, String>> argmax = transArgmax(nbest, wts), current = new ArrayList<ScoredFeaturizedTranslation<IString, String>>(argmax);

    // recover which candidates were selected
    int[] argmaxCandIds = new int[current.size()]; Arrays.fill(argmaxCandIds, -1);
    for (int i = 0; i < nbest.nbestLists().size(); i++) {
      for (int j = 0; j < nbest.nbestLists().get(i).size(); j++) {
        if (current.get(i) == nbest.nbestLists().get(i).get(j)) argmaxCandIds[i] = j;
      }
    }

    Scorer<String> scorer = new StaticScorer(wts);

    int cnt = 0;
    double dEEval = Double.POSITIVE_INFINITY;

    // expected value sum
    double sumExpL = 0.0;
    for (int batch = 0;
         (Math.abs(dEEval) > NO_PROGRESS_MCMC_TIGHT_DIFF
                 || batch < MCMC_MIN_BATCHES) &&
                 batch < MCMC_MAX_BATCHES_TIGHT;
         batch++) {

      double oldExpL = sumExpL/cnt;

      for (int bi = 0; bi < MCMC_BATCH_SAMPLES; bi++) {
        // gibbs mcmc sample
        if (cnt != 0)  // always sample once from argmax
          for (int sentId = 0; sentId < nbest.nbestLists().size(); sentId++) {
            double Z = 0;
            double[] num = new double[nbest.nbestLists().get(sentId).size()];
            int pos = -1; for (ScoredFeaturizedTranslation<IString, String> trans : nbest.nbestLists().get(sentId)) { pos++;
            Z += num[pos] = Math.exp(scorer.getIncrementalScore(trans.features));
          }

            int selection = -1;
            if (Z != 0) {
              double rv = random.nextDouble()*Z;
              for (int i = 0; i < num.length; i++) {
                if ((rv -= num[i]) <= 0) { selection = i; break; }
              }
            } else {
              selection = random.nextInt(num.length);
            }

            if (Z == 0) {
              Z = 1.0;
              num[selection] = 1.0/num.length;
            }
            ErasureUtils.noop(Z);

            if (selection == -1) 
              selection = random.nextInt(num.length);

            // adjust current
            current.set(sentId, nbest.nbestLists().get(sentId).get(selection));
          }

        // collect derivative relevant statistics using sample
        cnt++;

        // adjust currentF & eval
        double  eval = emetric.score(current);

        sumExpL += eval;
      }

      dEEval = (oldExpL != oldExpL ? Double.POSITIVE_INFINITY :
              oldExpL - sumExpL/cnt);

      System.err.printf("TBatch: %d dEEval: %e cnt: %d\n", batch, dEEval, cnt);
      System.err.printf("E(loss) = %e (sum: %e)\n", sumExpL/cnt, sumExpL);
    }

    // objective 0.5*||w||_2^2 - C * E(Eval), e.g. 0.5*||w||_2^2 - C * E(BLEU)
    double l2wts = Counters.L2Norm(wts);
    double obj = (C != 0 && regularize ? 0.5*l2wts*l2wts -C*sumExpL/cnt : -sumExpL/cnt);
    System.err.printf("Regularized objective 0.5*||w||_2^2 - C * E(Eval): %e\n", obj);
    System.err.printf("C: %e\n", C);
    System.err.printf("||w||_2^2: %e\n", l2wts*l2wts);
    System.err.printf("E(loss) = %e\n", sumExpL/cnt);
    return obj;
  }

  static public List<ScoredFeaturizedTranslation<IString,String>>
  transEvalArgmax(MosesNBestList nbest,
                  EvaluationMetric<IString, String> emetric) {
    MultiTranslationMetricMax<IString, String> oneBestSearch =
            new HillClimbingMultiTranslationMetricMax<IString, String>(emetric);
    return oneBestSearch.maximize(nbest);
  }

  public List<ScoredFeaturizedTranslation<IString, String>> randomBetterTranslations(
          MosesNBestList nbest, Counter<String> wts,
          EvaluationMetric<IString, String> emetric) {
    return randomBetterTranslations(nbest, transArgmax(nbest, wts), emetric);
  }

  public List<ScoredFeaturizedTranslation<IString, String>> randomBetterTranslations(
          MosesNBestList nbest,
          List<ScoredFeaturizedTranslation<IString, String>> current,
          EvaluationMetric<IString, String> emetric) {
    List<List<ScoredFeaturizedTranslation<IString, String>>> nbestLists = nbest
            .nbestLists();
    List<ScoredFeaturizedTranslation<IString, String>> trans = new ArrayList<ScoredFeaturizedTranslation<IString, String>>(
            nbestLists.size());
    IncrementalEvaluationMetric<IString, String> incEval = emetric
            .getIncrementalMetric();
    for (ScoredFeaturizedTranslation<IString, String> tran : current) {
      incEval.add(tran);
    }
    double baseScore = incEval.score();
    List<List<ScoredFeaturizedTranslation<IString, String>>> betterTrans = new ArrayList<List<ScoredFeaturizedTranslation<IString, String>>>(
            nbestLists.size());
    int lI = -1;
    for (List<ScoredFeaturizedTranslation<IString, String>> nbestlist : nbestLists) {
      lI++;
      betterTrans
              .add(new ArrayList<ScoredFeaturizedTranslation<IString, String>>());
      for (ScoredFeaturizedTranslation<IString, String> tran : nbestlist) {
        incEval.replace(lI, tran);
        if (incEval.score() >= baseScore)
          betterTrans.get(lI).add(tran);
      }
      incEval.replace(lI, current.get(lI));
    }

    for (List<ScoredFeaturizedTranslation<IString, String>> list : betterTrans) {
      trans.add(list.get(random.nextInt(list.size())));
    }

    return trans;
  }

  static class InterceptIDs {
    final int list;
    final int trans;

    InterceptIDs(int list, int trans) {
      this.list = list;
      this.trans = trans;
    }
  }

  @SuppressWarnings("deprecation")
  public Counter<String> lineSearch(MosesNBestList nbest,
                                    Counter<String> optWts, Counter<String> direction,
                                    EvaluationMetric<IString, String> emetric) {

    Counter<String> initialWts = optWts;
    if(fixedWts != null) {
      initialWts = new ClassicCounter<String>(optWts);
      initialWts.addAll(fixedWts);
    }

    Scorer<String> currentScorer = new StaticScorer(initialWts);
    Scorer<String> slopScorer = new StaticScorer(direction);
    ArrayList<Double> intercepts = new ArrayList<Double>();
    Map<Double, Set<InterceptIDs>> interceptToIDs = new HashMap<Double, Set<InterceptIDs>>();

    {
      int lI = -1;
      for (List<ScoredFeaturizedTranslation<IString, String>> nbestlist : nbest
              .nbestLists()) {
        lI++;
        // calculate slops/intercepts
        double[] m = new double[nbestlist.size()];
        double[] b = new double[nbestlist.size()];
        {
          int tI = -1;
          for (ScoredFeaturizedTranslation<IString, String> trans : nbestlist) {
            tI++;
            m[tI] = slopScorer.getIncrementalScore(trans.features);
            b[tI] = currentScorer.getIncrementalScore(trans.features);
          }
        }

        // find -inf*dir candidate
        int firstBest = 0;
        for (int i = 1; i < m.length; i++) {
          if (m[i] < m[firstBest]
                  || (m[i] == m[firstBest] && b[i] > b[firstBest])) {
            firstBest = i;
          }
        }

        Set<InterceptIDs> niS = interceptToIDs.get(Double.NEGATIVE_INFINITY);
        if (niS == null) {
          niS = new HashSet<InterceptIDs>();
          interceptToIDs.put(Double.NEGATIVE_INFINITY, niS);
        }

        niS.add(new InterceptIDs(lI, firstBest));

        // find & save all intercepts
        double interceptLimit = Double.NEGATIVE_INFINITY;
        for (int currentBest = firstBest; currentBest != -1;) {
          // find next intersection
          double nearestIntercept = Double.POSITIVE_INFINITY;
          int nextBest = -1;
          for (int i = 0; i < m.length; i++) {
            double intercept = (b[currentBest] - b[i])
                    / (m[i] - m[currentBest]); // wow just like middle school
            if (intercept <= interceptLimit + MIN_PLATEAU_DIFF)
              continue;
            if (intercept < nearestIntercept) {
              nextBest = i;
              nearestIntercept = intercept;
            }
          }
          if (nearestIntercept == Double.POSITIVE_INFINITY)
            break;
          if (DEBUG) {
            System.out.printf("Nearest intercept: %e Limit: %e\n",
                    nearestIntercept, interceptLimit);
          }
          intercepts.add(nearestIntercept);
          interceptLimit = nearestIntercept;
          Set<InterceptIDs> s = interceptToIDs.get(nearestIntercept);
          if (s == null) {
            s = new HashSet<InterceptIDs>();
            interceptToIDs.put(nearestIntercept, s);
          }
          s.add(new InterceptIDs(lI, nextBest));
          currentBest = nextBest;
        }
      }
    }

    // check eval score at each intercept;
    double bestEval = Double.NEGATIVE_INFINITY;
    //Counter<String> bestWts = initialWts;
    if (intercepts.size() == 0)
      return initialWts;
    intercepts.add(Double.NEGATIVE_INFINITY);
    Collections.sort(intercepts);
    resetQuickEval(emetric, nbest);
    System.out.printf("Checking %d points", intercepts.size() - 1);

    double[] evals = new double[intercepts.size()];
    double[] chkpts = new double[intercepts.size()];

    for (int i = 0; i < intercepts.size(); i++) {
      double chkpt;
      if (i == 0) {
        chkpt = intercepts.get(i + 1) - 1.0;
      } else if (i + 1 == intercepts.size()) {
        chkpt = intercepts.get(i) + 1.0;
      } else {
        if (intercepts.get(i) < 0 && intercepts.get(i + 1) > 0) {
          chkpt = 0;
        } else {
          chkpt = (intercepts.get(i) + intercepts.get(i + 1)) / 2.0;
        }
      }
      if (DEBUG)
        System.out.printf("intercept: %f, chkpt: %f\n", intercepts.get(i),
                chkpt);
      double eval = quickEvalAtPoint(nbest, interceptToIDs.get(intercepts
              .get(i)));

      chkpts[i] = chkpt;
      evals[i] = eval;

      if (DEBUG) {
        System.out.printf("pt(%d): %e eval: %e best: %e\n", i, chkpt, eval,
                bestEval);
      }
    }

    int bestPt = -1;
    for (int i = 0; i < evals.length; i++) {
      double eval = windowSmooth(evals, i, SEARCH_WINDOW);
      if (bestEval < eval) {
        bestPt = i;
        bestEval = eval;
      }
    }

    System.out.printf(" - best eval: %f\n", bestEval);

    Counter<String> newWts = new ClassicCounter<String>(initialWts);
    Counters.addInPlace(newWts, direction, chkpts[bestPt]);
    return removeWts(normalize(newWts), fixedWts);
  }

  enum SmoothingType {
    avg, min
  }

  static final int SEARCH_WINDOW = Integer.parseInt(System.getProperty(
          "SEARCH_WINDOW", "1"));
  static public int MIN_NBEST_OCCURANCES = Integer.parseInt(System.getProperty(
          "MIN_NBEST_OCCURENCES", "5"));
  //static int STARTING_POINTS = Integer.parseInt(System.getProperty(
  //        "STARTING_POINTS", "5")); //XXX
  static final SmoothingType smoothingType = SmoothingType.valueOf(System
          .getProperty("SMOOTHING_TYPE", "min"));
  static final boolean filterUnreachable = Boolean.parseBoolean(System
          .getProperty("FILTER_UNREACHABLE", "false"));

  static {
    System.err.println();
    System.err.printf("Search Window Size: %d\n", SEARCH_WINDOW);
    System.err.printf("Min nbest occurences: %d\n", MIN_NBEST_OCCURANCES);
    System.err.printf("Smoothing Type: %s\n", smoothingType);
    System.err.printf("Min plateau diff: %f\n", MIN_PLATEAU_DIFF);
    System.err.printf("Min objective diff: %f\n", MIN_OBJECTIVE_DIFF);
    System.err.printf("FilterUnreachable?: %b\n", filterUnreachable);
  }


  static double windowSmooth(double[] a, int pos, int window) {
    int strt = Math.max(0, pos - window);
    int nd = Math.min(a.length, pos + window + 1);

    if (smoothingType == SmoothingType.min) {
      int minLoc = strt;
      for (int i = strt + 1; i < nd; i++)
        if (a[i] < a[minLoc])
          minLoc = i;
      return a[minLoc];
    } else if (smoothingType == SmoothingType.avg) {
      double avgSum = 0;
      for (int i = strt; i < nd; i++)
        avgSum += a[i];

      return avgSum / (nd - strt);
    } else {
      throw new RuntimeException();
    }
  }

  public static Counter<String> summarizedAllFeaturesVector(
          List<ScoredFeaturizedTranslation<IString, String>> trans) {
    Counter<String> sumValues = new ClassicCounter<String>();

    for (ScoredFeaturizedTranslation<IString, String> tran : trans) {
      for (FeatureValue<String> fValue : tran.features) {
        sumValues.incrementCount(fValue.name, fValue.value);
      }
    }

    return sumValues;
  }

  static public List<ScoredFeaturizedTranslation<IString, String>> transArgmax(
          MosesNBestList nbest, Counter<String> wts) {
    Scorer<String> scorer = new StaticScorer(wts);
    MultiTranslationMetricMax<IString, String> oneBestSearch = new GreedyMultiTranslationMetricMax<IString, String>(
            new ScorerWrapperEvaluationMetric<IString, String>(scorer));
    return oneBestSearch.maximize(nbest);
  }

  public List<ScoredFeaturizedTranslation<IString, String>> randomTranslations(
          MosesNBestList nbest) {
    List<List<ScoredFeaturizedTranslation<IString, String>>> nbestLists = nbest
            .nbestLists();
    List<ScoredFeaturizedTranslation<IString, String>> trans = new ArrayList<ScoredFeaturizedTranslation<IString, String>>(
            nbestLists.size());

    for (List<ScoredFeaturizedTranslation<IString, String>> list : nbest
            .nbestLists()) {
      trans.add(list.get(random.nextInt(list.size())));
    }

    return trans;
  }

  static public double wtSsd(Counter<String> oldWts,
                             Counter<String> newWts) {
    double ssd = 0;
    for (String k : newWts.keySet()) {
      double diff = oldWts.getCount(k) - newWts.getCount(k);
      ssd += diff * diff;
    }
    return ssd;
  }

  @SuppressWarnings("deprecation")
  static public Counter<String> normalize(Counter<String> wts) {
    Counters.multiplyInPlace(wts, 1.0 / l1norm(wts));
    return wts;
  }

  static public double l1norm(Counter<String> wts) {
    double sum = 0;
    for (String f : wts.keySet()) {
      sum += Math.abs(wts.getCount(f));
    }

    return sum;
  }

  IncrementalEvaluationMetric<IString, String> quickIncEval;

  private void resetQuickEval(EvaluationMetric<IString, String> emetric,
                                     MosesNBestList nbest) {
    quickIncEval = emetric.getIncrementalMetric();
    int sz = nbest.nbestLists().size();
    for (int i = 0; i < sz; i++) {
      quickIncEval.add(null);
    }
  }

  /**
   * Specialized evalAt point just for line search
   *
   * Previously, profiling revealed that this was a serious hotspot
   *
   */
  private double quickEvalAtPoint(MosesNBestList nbest,
                                         Set<InterceptIDs> s) {
    if (DEBUG)
      System.out.printf("replacing %d points\n", s.size());
    for (InterceptIDs iId : s) {
      ScoredFeaturizedTranslation<IString, String> trans = nbest.nbestLists()
              .get(iId.list).get(iId.trans);
      quickIncEval.replace(iId.list, trans);
    }
    return quickIncEval.score();
  }

  private final static boolean FAST_STATIC_SCORER = System.getProperty("fastStaticScorer") != null;
  static {
    System.err.println("fast static scorer: "+FAST_STATIC_SCORER);
  }

  static public double evalAtPoint(MosesNBestList nbest,
                                   Counter<String> optWts, EvaluationMetric<IString, String> emetric) {
    Counter<String> wts = optWts;
    if(fixedWts != null) {
      wts = new ClassicCounter<String>(optWts);
      removeWts(wts, fixedWts);
      wts.addAll(fixedWts);
    }
    Scorer<String> scorer = new StaticScorer(wts, nbest.featureIndex);
    if(DEBUG)
      System.err.printf("eval at point (%d,%d): %s\n", optWts.size(), wts.size(), wts.toString());
    IncrementalEvaluationMetric<IString, String> incEval = emetric
            .getIncrementalMetric();
    IncrementalNBestEvaluationMetric<IString, String> incNBestEval = null;
    boolean isNBestEval = false;
    if(incEval instanceof IncrementalNBestEvaluationMetric) {
      incNBestEval = (IncrementalNBestEvaluationMetric<IString,String>) incEval;
      isNBestEval = true;
    }
    for (int i = 0; i < nbest.nbestLists().size(); i++) {
      List<ScoredFeaturizedTranslation<IString, String>> nbestlist = nbest.nbestLists().get(i);
      ScoredFeaturizedTranslation<IString, String> highestScoreTrans = null;
      double highestScore = Double.NEGATIVE_INFINITY;
      int highestIndex = -1;
      for (int j = 0; j < nbestlist.size(); j++) {
        ScoredFeaturizedTranslation<IString, String> trans = nbestlist.get(j);
        double score = scorer.getIncrementalScore(trans.features);
        if (score > highestScore) {
          highestScore = score;
          highestScoreTrans = trans;
          highestIndex = j;
        }
      }
      if(isNBestEval)
        incNBestEval.add(highestIndex, highestScoreTrans);
      else
        incEval.add(highestScoreTrans);
    }
    double score = incEval.score();
    updateBest(wts, -score);
    return score;
  }

  public static Counter<String> readWeights(String filename) throws IOException {
    BufferedReader reader = new BufferedReader(new FileReader(filename));
    Counter<String> wts = new ClassicCounter<String>();
    for (String line = reader.readLine(); line != null; line = reader
            .readLine()) {
      String[] fields = line.split("\\s+");
      wts.incrementCount(fields[0], Double.parseDouble(fields[1]));
    }
    reader.close();
    return wts;
  }

  @SuppressWarnings("deprecation")
  static void writeWeights(String filename, Counter<String> wts)
          throws IOException {
    BufferedWriter writer = new BufferedWriter(new FileWriter(filename));

    Counter<String> wtsMag = new ClassicCounter<String>();
    for (String w : wts.keySet()) {
      wtsMag.setCount(w, Math.abs(wts.getCount(w)));
    }

    for (String f : Counters.toPriorityQueue(wtsMag).toSortedList()) {
      double cnt = wts.getCount(f);
      if(cnt != 0.0)
        writer.append(f).append(" ").append(Double.toString(cnt)).append("\n");
    }
    writer.close();
  }

  static void displayWeights(Counter<String> wts) {
    for (String f : wts.keySet()) {
      System.out.printf("%s %g\n", f, wts.getCount(f));
    }
  }

  static void displayWeightsOneLine(Counter<String> wts) {
    System.out.print("[ ");
    for (String f : wts.keySet()) {
      System.out.printf("%s=%g ", f, wts.getCount(f));
    }
    System.out.print("]");
  }

  static Counter<String> randomWts(Set<String> keySet) {
    Counter<String> randpt = new ClassicCounter<String>();
    for (String f : keySet) {
      if (generativeFeatures.contains(f)) {
        randpt.setCount(f, globalRandom.nextDouble());
      } else {
        randpt.setCount(f, globalRandom.nextDouble() * 2 - 1.0);
      }
    }

    System.err.printf("random Wts: %s\n", randpt);
    return randpt;
  }
 
  static int nInitialStartingPoints;
  final static Queue<Counter<String>> startingPoints = new LinkedList<Counter<String>>();

  static MosesNBestList nbest;
  static long startTime;

  static Counter<String> initialWts;
  static List<Counter<String>> previousWts;

  static Counter<String> fixedWts = null;
  static Counter<String> bestWts;
  static double bestObj = Double.POSITIVE_INFINITY;

  static double initialObjValue;
  static boolean mcmcObj;

  static boolean reuseWeights;
  static double nbestEval;
  static double initialEval;

  /**
   * Initialize everything that is read only, i.e., nbest list, starting points.
   * @throws IOException
   */
  public static void initStatic(String nbestListFile, String localNbestListFile, String previousWtsFiles, int nStartingPoints, UnsmoothedMERT defaultMERT) throws IOException {

    startTime = System.currentTimeMillis();

    EvaluationMetric<IString, String> emetric = defaultMERT.emetric;

    // Load nbest list:
    nbest = new MosesNBestList(nbestListFile, tokenizeNIST);
    //nbest.setArraysFromIndex();
    MosesNBestList localNbest = new MosesNBestList(localNbestListFile, nbest.sequenceSelfMap, tokenizeNIST);
    //localNbest.setArraysFromIndex();
    AbstractNBestOptimizer.nbest = nbest;


    // Load weight files:
    previousWts = new ArrayList<Counter<String>>();
    for(String previousWtsFile : previousWtsFiles.split(","))
      previousWts.add(removeWts(readWeights(previousWtsFile), fixedWts));
    initialWts = previousWts.get(0);

    for (int i = 0; i < nStartingPoints; i++) {
      Counter<String> wts;
      if (i == 0) {
        wts = initialWts;
      } else {
        if(i < previousWts.size()) {
          wts = previousWts.get(i);
        } else {
          wts = randomWts(initialWts.keySet());
        }
      }
      startingPoints.add(wts);
    }
    nInitialStartingPoints = startingPoints.size();

    mcmcObj = (System.getProperty("mcmcELossDirExact") != null ||
        System.getProperty("mcmcELossSGD") != null ||
        System.getProperty("mcmcELossCG") != null);

    if (mcmcObj) {
      initialObjValue = defaultMERT.mcmcTightExpectedEval(nbest, initialWts, emetric);
    } else {
      initialObjValue = nbestEval;
    }

    Scorer<String> scorer = new StaticScorer(initialWts);

    List<ScoredFeaturizedTranslation<IString, String>> localNbestArgmax = transArgmax(localNbest, initialWts);
    List<ScoredFeaturizedTranslation<IString, String>> nbestArgmax = transArgmax(nbest, initialWts);
    double localNbestEval = emetric.score(localNbestArgmax);
    nbestEval    = emetric.score(nbestArgmax);
    reuseWeights = Math.abs(localNbestEval - nbestEval) < MAX_LOCAL_ALL_GAP_WTS_REUSE;
    System.err.printf("Eval: %f Local eval: %f\n", nbestEval, localNbestEval);
    System.err.printf("Rescoring entries\n");
    // rescore all entries by weights
    System.err.printf("n-best list sizes %d, %d\n", localNbest.nbestLists()
            .size(), nbest.nbestLists().size());
    if (localNbest.nbestLists().size() != nbest.nbestLists().size()) {
      System.err
              .printf(
                      "Error incompatible local and cummulative n-best lists, sizes %d != %d\n",
                      localNbest.nbestLists().size(), nbest.nbestLists().size());
      System.exit(-1);
    }
    {
      int lI = -1;
      for (List<ScoredFeaturizedTranslation<IString, String>> nbestlist : nbest
              .nbestLists()) {
        lI++;
        List<ScoredFeaturizedTranslation<IString, String>> lNbestList = localNbest
                .nbestLists().get(lI);
        // If we wanted, we could get the value of minReachableScore by just
        // checking the bottom of the n-best list.
        // However, lets make things robust to the order of the entries in the
        // n-best list being mangled as well as
        // score rounding.
        double minReachableScore = Double.POSITIVE_INFINITY;
        for (ScoredFeaturizedTranslation<IString, String> trans : lNbestList) {
          double score = scorer.getIncrementalScore(trans.features);
          if (score < minReachableScore)
            minReachableScore = score;
        }
				if(nbestlist.size() == 0)
					throw new RuntimeException(String.format("Nbest list of size zero at %d. Perhaps PseudoMoses ran out of memory?\n", lI));
        System.err.printf("l %d - min reachable score: %f (orig size: %d)\n",
                lI, minReachableScore, nbestlist.size());
        for (ScoredFeaturizedTranslation<IString, String> trans : nbestlist) {
          trans.score = scorer.getIncrementalScore(trans.features);
          if (filterUnreachable && trans.score > minReachableScore) { // mark as
            // potentially
            // unreachable
            trans.score = Double.NaN;
          }
        }
      }
    }

    System.err.printf("removing anything that might not be reachable\n");
    // remove everything that might not be reachable
    for (int lI = 0; lI < nbest.nbestLists().size(); lI++) {
      List<ScoredFeaturizedTranslation<IString, String>> newList = new ArrayList<ScoredFeaturizedTranslation<IString, String>>(
              nbest.nbestLists().get(lI).size());
      List<ScoredFeaturizedTranslation<IString, String>> lNbestList = localNbest
              .nbestLists().get(lI);

      for (ScoredFeaturizedTranslation<IString, String> trans : nbest
              .nbestLists().get(lI)) {
        if (trans.score == trans.score)
          newList.add(trans);
      }
      if (filterUnreachable)
        newList.addAll(lNbestList); // otherwise entries are
      // already on the n-best list
      nbest.nbestLists().set(lI, newList);
      System.err.printf(
              "l %d - final (filtered) combined n-best list size: %d\n", lI,
              newList.size());
    }

    // add entries for all wts in n-best list
    for (List<ScoredFeaturizedTranslation<IString, String>> nbestlist : nbest
            .nbestLists()) {
      for (ScoredFeaturizedTranslation<IString, String> trans : nbestlist) {
        for (FeatureValue<String> f : trans.features) {
          initialWts.incrementCount(f.name, 0);
        }
      }
    }

    if (System.getProperty("C") != null) {
      C = Double.parseDouble(System.getProperty("C"));
      System.err.printf("Using C %f rather than default of %f\n",
              C, DEFAULT_C);
    }

    if (System.getProperty("T") != null) {
      T = Double.parseDouble(System.getProperty("T"));
      System.err.printf("Using T %f rather than default of %f\n", T, DEFAULT_T);
    }

    lrate = (C != 0 ? DEFAULT_UNSCALED_L_RATE/C : DEFAULT_UNSCALED_L_RATE);
    System.out.printf("sgd lrate: %e\n", lrate);

    if(reuseWeights) {
      System.err.printf("Re-using initial wts, gap: %e", Math.abs(localNbestEval - nbestEval));
    } else {
      System.err.printf("*NOT* Re-using initial wts, gap: %e max gap: %e", Math.abs(localNbestEval - nbestEval), MAX_LOCAL_ALL_GAP_WTS_REUSE);
    }

    removeWts(initialWts, fixedWts);
    initialEval = evalAtPoint(nbest, initialWts, emetric);
    System.out.printf("Initial Eval Score: %e\n", initialEval);
    System.out.printf("Initial Weights:\n==================\n");
    displayWeights(initialWts);
  }

  final EvaluationMetric<IString, String> emetric;
  final String optStr;
  final String seedStr;

  Random random;

  
	@SuppressWarnings("unchecked")
	public UnsmoothedMERT(String evalMetric, String referenceList, String optStr, String seedStr) throws IOException {

    this.optStr = optStr;
    this.seedStr = seedStr;

    TERpMetric.BEAM_WIDTH = 5; // XXX - make cleaner/safer
		TERpMetric.MAX_SHIFT_DIST = 10; // XXX - make cleaner/safer
		if (evalMetric.equals("bleu:3-2terp")) {
    	List<List<Sequence<IString>>> referencesBleu = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
    	List<List<Sequence<IString>>> referencesTERp = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
			int BLEUOrder = 3;
      double terW = 2.0;
      
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, terW},
                      new BLEUMetric<IString, String>(referencesBleu, BLEUOrder, smoothBLEU),
                      new TERpMetric<IString, String>(referencesTERp));
      System.err.printf("Maximizing %s: BLEU:3 minus 2*TERp (terW=%f)\n", evalMetric, terW);
    } else if (evalMetric.equals("bleu:3-terp")) {
      List<List<Sequence<IString>>> referencesBleu = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      List<List<Sequence<IString>>> referencesTERp = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      int BLEUOrder = 3;
      double terW = 1.0;
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, terW},
                      new BLEUMetric<IString, String>(referencesBleu, BLEUOrder, smoothBLEU),
                      new TERpMetric<IString, String>(referencesTERp));
      System.err.printf("Maximizing %s: BLEU:3 minus 1*TERp (terW=%f)\n", evalMetric, terW);
    } else if (evalMetric.equals("terp")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      emetric = new TERpMetric<IString, String>(references);
    } else if (evalMetric.equals("terpa")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      emetric = new TERpMetric<IString, String>(references, false, true);
    } else if (evalMetric.equals("meteor") || evalMetric.startsWith("meteor:")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
			if (fields.length > 1) {
				double alpha = Double.parseDouble(fields[1]);
      	double beta = Double.parseDouble(fields[2]);
      	double gamma = Double.parseDouble(fields[3]);
      	emetric = new METEORMetric<IString, String>(references, alpha, beta, gamma);
			} else {
      	emetric = new METEORMetric<IString, String>(references);
			}
    } else if (evalMetric.equals("ter") || evalMetric.startsWith("ter:")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
      TERMetric<IString,String> termetric = new TERMetric<IString, String>(references);
      setFastTER(termetric.calc);
      if (fields.length > 1) {
        int beamWidth = Integer.parseInt(fields[1]);
        termetric.calc.setBeamWidth(beamWidth);
        System.err.printf("TER beam width set to %d (default: 20)\n",beamWidth);
        if (fields.length > 2) {
          int maxShiftDist = Integer.parseInt(fields[2]);
          termetric.calc.setShiftDist(maxShiftDist);
          System.err.printf("TER maximum shift distance set to %d (default: 50)\n",maxShiftDist);
        }
      }
      emetric = termetric;
    } else if (evalMetric.equals("bleu") || evalMetric.startsWith("bleu:")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      if (evalMetric.contains(":")) {
				String[] fields = evalMetric.split(":");
				int BLEUOrder = Integer.parseInt(fields[1]);
      	emetric = new BLEUMetric<IString, String>(references, BLEUOrder, smoothBLEU);
			} else {
      	emetric = new BLEUMetric<IString, String>(references, smoothBLEU);
			}
    } else if (evalMetric.equals("nist")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
       emetric = new NISTMetric<IString, String>(references);
    } else if (evalMetric.startsWith("bleu-2terp")) {
    	List<List<Sequence<IString>>> referencesBleu = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
    	List<List<Sequence<IString>>> referencesTERp = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
      double terW = 2.0;
      if(fields.length > 1) {
        assert(fields.length == 2);
        terW = Double.parseDouble(fields[1]);
      }
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, terW},
                      new BLEUMetric<IString, String>(referencesBleu, smoothBLEU),
                      new TERpMetric<IString, String>(referencesTERp));
      System.err.printf("Maximizing %s: BLEU minus TERpA (terW=%f)\n", evalMetric, terW);
    } else if (evalMetric.startsWith("bleu+2meteor")) {
    	List<List<Sequence<IString>>> referencesBleu = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
    	List<List<Sequence<IString>>> referencesMeteor = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
			double alpha = 0.95, beta = 0.5, gamma = 0.5;
      if(fields.length > 1) {
        assert(fields.length == 4);
        alpha = Double.parseDouble(fields[1]);
        beta = Double.parseDouble(fields[2]);
        gamma = Double.parseDouble(fields[2]);
      }
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, 2.0},
                      new BLEUMetric<IString, String>(referencesBleu, smoothBLEU),
                      new METEORMetric<IString, String>(referencesMeteor, alpha, beta, gamma));
      System.err.printf("Maximizing %s: BLEU + 2*METEORTERpA (meteorW=%f)\n",
              evalMetric, 2.0);
    } else if (evalMetric.startsWith("bleu-2terpa")) {
    	List<List<Sequence<IString>>> referencesBleu = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
    	List<List<Sequence<IString>>> referencesTERpa = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
      double terW = 2.0;
      if(fields.length > 1) {
        assert(fields.length == 2);
        terW = Double.parseDouble(fields[1]);
      }
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, terW},
                      new BLEUMetric<IString, String>(referencesBleu, smoothBLEU),
                      new TERpMetric<IString, String>(referencesTERpa, false, true));
      System.err.printf("Maximizing %s: BLEU minus TERpA (terW=%f)\n", evalMetric, terW);
    } else if (evalMetric.startsWith("bleu-ter")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      String[] fields = evalMetric.split(":");
      double terW = 1.0;
      if(fields.length > 1) {
        assert(fields.length == 2);
        terW = Double.parseDouble(fields[1]);
      }
      TERMetric termetric = new TERMetric<IString, String>(references);
      setFastTER(termetric.calc);
      emetric = new LinearCombinationMetric<IString, String>
              (new double[] {1.0, terW}, new BLEUMetric<IString, String>(references, smoothBLEU), termetric);
      System.err.printf("Maximizing %s: BLEU minus TER (terW=%f)\n", evalMetric, terW);
    } else if (evalMetric.equals("wer")) {
    	List<List<Sequence<IString>>> references = Metrics
            .readReferences(referenceList.split(","), tokenizeNIST);
      emetric = new WERMetric<IString, String>(references);
    } else {
      emetric = null;
      System.err.printf("Unrecognized metric: %s\n", evalMetric);
      System.exit(-1);
    }
  }

  static void setFastTER(TERcalc calc) {
    if (System.getProperty("fastTER") != null) {
      System.err.println("beam width: "+UnsmoothedMERT.DEFAULT_TER_BEAM_WIDTH);
      System.err.println("ter shift dist: "+UnsmoothedMERT.DEFAULT_TER_SHIFT_DIST);
      calc.setBeamWidth(UnsmoothedMERT.DEFAULT_TER_BEAM_WIDTH);
      calc.setShiftDist(UnsmoothedMERT.DEFAULT_TER_SHIFT_DIST);
    }
  }

  static boolean updateBest(Counter<String> newWts, double obj) {
    synchronized(UnsmoothedMERT.class) {
      if (bestObj > obj) {
        System.err.printf("\n<<<IMPROVED BEST: %f -> %f with {{{%s}}}.>>>\n", -bestObj, -obj, newWts);
        bestWts = newWts;
        bestObj = obj;
        return true;
      }
      return false;
    }
  }

  static Counter<String> removeWts(Counter<String> wts, Counter<String> fixedWts) {
    if(fixedWts != null)
      for (String s : fixedWts.keySet())
        wts.remove(s);
    return wts;
  }

  public void run() {

    System.out.printf("\nthread started (%d): %s\n", startingPoints.size(), this);

    for(;;) {

      Counter<String> wts;

      int sz;
      synchronized(startingPoints) {
        sz = startingPoints.size();
        wts = startingPoints.poll();
      }
      if(wts == null)
        break;

      int ptI = nInitialStartingPoints - sz;

      // Make the seed a function of current starting point, to
      // ensure experiments are reproducible:
      List<Double> v = new ArrayList<Double>(wts.values());
      Collections.sort(v);
      v.add(SEED*1.0); 
      long threadSeed = Arrays.hashCode(v.toArray());
      this.random = new Random(threadSeed);

      System.out.printf("\npoint %d - initial wts: %s", ptI, wts.toString());
      System.out.printf("\npoint %d - seed: %d\n", ptI, threadSeed);
      
      NBestOptimizer opt = NBestOptimizerFactory.factory(optStr, this);
      System.err.println("using: "+opt.toString());

      // Make sure weights that shouldn't be optimized are not in wts:
      removeWts(wts, fixedWts);
      Counter<String> optWts = opt.optimize(wts);
      // Temporarily add them back before normalization:
      if(fixedWts != null) optWts.addAll(fixedWts);
      Counter<String> newWts = normalize(optWts);
      // Remove them again:
      removeWts(newWts, fixedWts);

      double evalAt = evalAtPoint(nbest, newWts, emetric);
      double mcmcEval = mcmcTightExpectedEval(nbest, newWts, emetric);
      double mcmcEval2 = mcmcTightExpectedEval(nbest, bestWts, emetric, false);

      double obj = (mcmcObj ? mcmcEval : -evalAt);

      System.out.printf("\npoint %d - final wts: %s", ptI, newWts.toString());
      System.out.printf("\npoint %d - eval: %e E(eval): %e obj: %e best obj: %e (l1: %f)\n\n",
                        ptI, evalAt, mcmcEval2, obj, bestObj, l1norm(newWts));
    }
  }

  public void save(String finalWtsFile) throws IOException {

    double finalObjValue = (mcmcObj ?
            mcmcTightExpectedEval(nbest, bestWts, emetric) :
            evalAtPoint(nbest, bestWts, emetric));

    double finalEval = evalAtPoint(nbest, bestWts, emetric);

    System.out.printf("Obj diff: %e\n", Math.abs(initialObjValue - finalObjValue));

    long endTime = System.currentTimeMillis();
    System.out.printf("Optimization Time: %.3f s\n",
            (endTime - startTime) / 1000.0);

    System.out.printf("Final Eval Score: %e->%e\n", initialEval, finalEval);
    System.out.printf("Final Obj: %e->%e\n", initialObjValue, finalObjValue);
    System.out.printf("Final Weights:\n==================\n");

    double wtSsd = wtSsd(initialWts, bestWts);

    if(fixedWts != null) {
      removeWts(bestWts, fixedWts);
      bestWts.addAll(fixedWts);
    }

    displayWeights(bestWts);
    System.out.printf("wts ssd: %e\n", wtSsd);
    writeWeights(finalWtsFile, bestWts);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {

    String optStr = "cer";
    String seedStr = "mert";
    int nStartingPoints = 5;
    int nThreads = 1;

    int argi = 0;
    String arg;

    while((arg = args[argi]).startsWith("-")) {
      if(arg.equals("-S")) {
        smoothBLEU = true;
      } else if(arg.equals("-s")) {
        seedStr = args[++argi];
      } else if(arg.equals("-p")) {
        nStartingPoints = Integer.parseInt(args[++argi]);
      } else if(arg.equals("-o")) {
        optStr = args[++argi];
      } else if(arg.equals("-N")) {
        tokenizeNIST = true;
      } else if(arg.equals("-f")) {
        String fixedWtsFile = args[++argi];
        try {
          fixedWts = readWeights(fixedWtsFile);
        } catch(IOException e) {
          System.err.println("Fixed weight file missing: "+fixedWtsFile);
          fixedWts = null;
        }
      } else if(arg.equals("-t")) {
        nThreads = Integer.parseInt(args[++argi]);
      } else {
        throw new UnsupportedOperationException("Unknown flag: "+arg);
      }
      ++argi;
    }

    if(args.length-argi != 6) {
      System.err.printf("Usage:\n\tjava mt.UnsmoothedMERT [-N] [-t (nb of threads)] [-s (seed)] [-p (nb of starting points)] [-o (optimizer name)] (eval metric) (nbest list) (local n-best) (file w/initial weights) (reference list); (new weights file)\n");
      System.err.println("-s <N>: provide seed to initialize random number generator.");
      System.err.println("-p <N>: number of starting points.");
      System.err.println("-o <N>: search algorithm.");
      System.err.println("-t <N>: number of threads.");
      System.err.println("-f <file>: weights read from file remain fixed during MERT.");
      System.err.println("-S: tune using sentence-level BLEU (smoothed).");
      System.err.println("-N: apply NIST tokenization to hypotheses.");
      System.exit(-1);
    }

    SEED = seedStr.hashCode();
    System.err.println("Seed used to generate random points: "+SEED);
    globalRandom = new Random(SEED);

    String evalMetric = args[argi].toLowerCase();
    String nbestListFile = args[++argi];
    String localNbestListFile = args[++argi];
    String previousWtsFiles = args[++argi];
    String referenceList = args[++argi];
    String finalWtsFile = args[++argi];

    UnsmoothedMERT mert = new UnsmoothedMERT(evalMetric, referenceList, optStr, seedStr);
    System.err.printf("Starting points: %d\n", nStartingPoints);
    System.err.printf("Threads: %d\n", nThreads);

    // Initialize static members (nbest list, etc); need UnsmoothedMERT instance for filtering the nbest list:
    initStatic(nbestListFile, localNbestListFile, previousWtsFiles, nStartingPoints, mert);

    List<Thread> threads = new ArrayList<Thread>(nThreads);
    for(int i=0; i<nThreads; ++i) {
      UnsmoothedMERT thread = (i==0) ? mert : new UnsmoothedMERT(evalMetric, referenceList, optStr, seedStr);
      thread.start();
      threads.add(thread);
    }
    for(int i=0; i<nThreads; ++i)
      threads.get(i).join();
    
    mert.save(finalWtsFile);
  }
}
