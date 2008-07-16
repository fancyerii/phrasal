package mt.train;

import java.util.Set;
import java.util.TreeSet;
import java.io.*;

import edu.stanford.nlp.util.IString;
import edu.stanford.nlp.util.IStrings;
import mt.base.Sequence;
import mt.base.SimpleSequence;

/**
  * Sentence pair with symmetrical word alignment (i.e., if e_i aligns to f_j in one direction, 
  * then f_j aligns to e_i as well in the other direction). If this is not what you want, use
  * GIZAWordAlignment.
  * 
  * @author Michel Galley
  * @see WordAlignment
  * @see GIZAWordAlignment
  */

public class SymmetricalWordAlignment extends AbstractWordAlignment {

  public static final String DEBUG_PROPERTY = "DebugWordAlignment";
  public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty(DEBUG_PROPERTY, "false"));

  public static final String VDEBUG_PROPERTY = "VerboseDebugWordAlignment";
  public static final boolean VERBOSE_DEBUG = Boolean.parseBoolean(System.getProperty(VDEBUG_PROPERTY, "false"));

  public SymmetricalWordAlignment() {
    if(DEBUG)
      System.err.println("SymmetricalWordAlignment: new instance.");
  }

  SymmetricalWordAlignment(Sequence<IString> f, Sequence<IString> e,
                        Set<Integer>[] f2e, Set<Integer>[] e2f) {
    super(f,e,f2e,e2f);
  }

  public SymmetricalWordAlignment(Integer id, String fStr, String eStr, String aStr) throws IOException {
    init(id,fStr,eStr,aStr);
  }
  
  public SymmetricalWordAlignment(String fStr, String eStr, String aStr, boolean s2t, boolean oneIndexed) throws IOException {
    init(fStr,eStr,aStr,s2t,oneIndexed);
  }

  public SymmetricalWordAlignment(String fStr, String eStr, String aStr) throws IOException {
    init(fStr,eStr,aStr);
  }

  public SymmetricalWordAlignment(Sequence<IString> f, Sequence<IString> e) {
    this.f = f; this.e = e;
    initAlignment();
  }

  public void init(Integer id, String fStr, String eStr, String aStr) throws IOException {
    this.id = id;
    init(fStr,eStr,aStr);
  }

  public void init(String fStr, String eStr, String aStr) throws IOException {
    init(fStr, eStr, aStr, false);
  }

  public void init(String fStr, String eStr, String aStr, boolean reverse) throws IOException {
    init(fStr, eStr, aStr, reverse, false);
  }

  public void init(String fStr, String eStr, String aStr, boolean reverse, boolean oneIndexed) throws IOException {
    if(VERBOSE_DEBUG)
      System.err.printf("f: %s\ne: %s\nalign: %s\n", fStr, eStr, aStr);
    f = new SimpleSequence<IString>(true, IStrings.toIStringArray(preproc(fStr.split("\\s+"))));
    e = new SimpleSequence<IString>(true, IStrings.toIStringArray(preproc(eStr.split("\\s+"))));
    initAlignment();
    if(aStr == null) {
      System.err.println("Warning: empty line.");
      return;
    }
    for(String al : aStr.split("\\s+")) {
      //System.err.printf("xx: <%s>\n",al);
      String[] els = al.split("-");
      if(els.length == 2) {
        int fpos = reverse ? Integer.parseInt(els[1]) : Integer.parseInt(els[0]);
        int epos = reverse ? Integer.parseInt(els[0]) : Integer.parseInt(els[1]);
        if(oneIndexed) { --fpos; --epos; }
        if(0 > fpos || fpos >= f.size())
          throw new IOException("f has index out of bounds (fsize="+f.size()+",esize="+e.size()+") : "+fpos);
        if(0 > epos || epos >= e.size())
          throw new IOException("e has index out of bounds (esize="+e.size()+",fsize="+f.size()+") : "+epos);
        f2e[fpos].add(epos);
        e2f[epos].add(fpos);
        if(VERBOSE_DEBUG) {
          System.err.println
           ("word alignment: ["+f.get(fpos)+"] -> ["+e.get(epos)+"]");
          System.err.println
           ("with indices: ("+fpos+")["+f.get(fpos)+"] -> ("+epos+")["+e.get(epos)+"]");
        }
      } else {
        System.err.println("Warning: bad alignment token: "+al);
      }
    }
    if(VERBOSE_DEBUG)
      System.err.println("sentence alignment: "+toString());
  }

  @SuppressWarnings("unchecked")
  private void initAlignment() {
    f2e = new TreeSet[f.size()];
    e2f = new TreeSet[e.size()];
    for(int i=0; i<f2e.length; ++i)
      f2e[i] = new TreeSet();
    for(int i=0; i<e2f.length; ++i)
      e2f[i] = new TreeSet();
  }
  
  public void addAlign(int f, int e) {
    f2e[f].add(e);
    e2f[e].add(f);
  }

  /**
	 * Compute alignment error rate. Since there is (currently) no S vs. P distinction
   * alignment in this class, 
	 * AER is 1 minus F-measure.
	 */
	static double computeAER(SymmetricalWordAlignment[] ref, SymmetricalWordAlignment[] hyp) {
		int tpC = 0, refC = 0, hypC = 0;
    double totalPrec = 0.0, totalRecall = 0.0, totalF = 0.0;
    if(ref.length != hyp.length)
     throw new RuntimeException("Not same number of aligned sentences!");
    for(int i=0; i<ref.length; ++i) {
      int _tpC = 0, _refC = 0, _hypC = 0;
      SymmetricalWordAlignment r = ref[i], h = hyp[i];
      assert(r.f().equals(h.f()));
      assert(r.e().equals(h.e()));
      for(int j=0; j<r.fSize(); ++j) {
        for(int k : r.f2e(j)) {
          if(h.f2e(j).contains(k))
            ++_tpC;
        }
        _refC += r.f2e(j).size();
        _hypC += h.f2e(j).size();
      }
      tpC += _tpC;
      refC += _refC;
      hypC += _hypC;
      double _prec = (_hypC > 0) ? _tpC*1.0/_hypC : 0;
      double _recall = (_refC > 0) ?  _tpC*1.0/_refC : 0;
      double _f = (_prec+_recall > 0) ? 2*_prec*_recall/(_prec+_recall) : 0.0;
      totalPrec += _prec;
      totalRecall += _recall;
      totalF += _f;
      if(DEBUG) {
        int len = r.f().size()+r.e().size();
        System.err.printf("sent\t%d\t%g\t%g\t%g\n", len, _prec, _recall, _f);
      }
    }
    double prec = tpC*1.0/hypC;
    double recall = tpC*1.0/refC;
    double fMeasure = 2*prec*recall/(prec+recall);
    if(DEBUG) {
      System.err.printf("micro: Precision = %.3g, Recall = %.3g, F = %.3g (TP=%d, HC=%d, RC=%d)\n", 
        prec, recall, fMeasure, tpC, hypC, refC);
      System.err.printf("macro: Precision = %.3g, Recall = %.3g, F = %.3g\n",
        totalPrec/ref.length, totalRecall/ref.length, totalF/ref.length);
    }
    return 1-fMeasure;
  }

  public String toString() { return toString(f2e); }
  public String toString1() { return toString(f2e,false); }

  public String toReverseString() { return toString(e2f); }
  public String toReverseString1() { return toString(e2f,false); }

  static public SymmetricalWordAlignment[] readFromIBMWordAlignment(String xmlFile) {
    InputStream in=null;
    IBMWordAlignmentHandler h=null;
    try {
			h = new IBMWordAlignmentHandler();
			in = new BufferedInputStream(new FileInputStream(new File(xmlFile)));
			h.readXML(in);
		} catch (Throwable t) {
			t.printStackTrace();
		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (IOException ioe) {
					ioe.printStackTrace();
				}
			}
		}
    return h.getIBMWordAlignment();
  }
}
