package com.mfec.dac.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The user documentation the assistant answers "how does ARAK work" from.
 *
 * <p>The files are the ones in {@code docs/}, packed into the jar under
 * {@code help/} at build time, so what a person reads and what the assistant
 * quotes are the same text. Each is cut into its {@code ##} sections, which the
 * guide writes to stand on their own, and a question is answered with the few
 * sections that match its keywords best.
 *
 * <p>Plain keyword matching, not a model: the guide is small, and a section
 * either names the thing or it does not. Matching is by substring, so
 * {@code mask} finds "masked" and "masking", and a Thai word finds itself in
 * Thai text that has no spaces between words.
 */
public final class HelpDocs {

  private static final Logger LOG = LoggerFactory.getLogger(HelpDocs.class);

  /** The files packed into the jar, most used first. */
  static final List<String> BUNDLED_FILES =
      List.of("user-guide.md", "policy-conflict-resolution.md", "policy-spec.md");

  /** Sections one answer carries. */
  static final int MAX_SECTIONS = 3;

  /** How much of one section the model is shown. */
  static final int MAX_SECTION = 4000;

  /** Keywords one question is searched for. */
  static final int MAX_TERMS = 8;

  private static final Set<String> STOP_WORDS =
      Set.of(
          "the", "and", "for", "how", "what", "why", "who", "when", "where", "which", "can",
          "does", "did", "are", "was", "were", "has", "have", "with", "from", "this", "that",
          "there", "into", "about", "you", "your", "our", "its", "not", "but", "any", "all",
          "get", "use", "arak", "please", "should", "would", "could", "will", "way", "work",
          "works", "mean", "means");

  /** One {@code ##} section of one document. */
  public record Section(String document, String heading, String text) {}

  private final List<Section> sections;

  public HelpDocs(List<Section> sections) {
    this.sections = List.copyOf(sections);
  }

  /** The documentation packed into this build; empty when it is not. */
  public static HelpDocs bundled() {
    return Holder.BUNDLED;
  }

  private static final class Holder {
    static final HelpDocs BUNDLED = load();
  }

  private static HelpDocs load() {
    List<Section> all = new ArrayList<>();
    for (String file : BUNDLED_FILES) {
      try (InputStream in = HelpDocs.class.getResourceAsStream("/help/" + file)) {
        if (in == null) {
          LOG.warn("help/{} is not in this build; the assistant cannot quote it", file);
          continue;
        }
        all.addAll(parse(file, new String(in.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException e) {
        LOG.warn("help/{} could not be read: {}", file, e.getMessage());
      }
    }
    return new HelpDocs(all);
  }

  public List<Section> sections() {
    return sections;
  }

  /**
   * Cuts one Markdown file into its {@code ##} sections. What comes before the
   * first one is the document's introduction, under its {@code #} title.
   * Deeper headings stay inside their section; a heading inside a code fence
   * is not a heading.
   */
  public static List<Section> parse(String file, String markdown) {
    String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
    String title = file;
    for (String line : lines) {
      if (line.startsWith("# ")) {
        title = line.substring(2).trim();
        break;
      }
    }
    String document = title.equals(file) ? file : title + " (" + file + ")";

    List<Section> out = new ArrayList<>();
    String heading = title;
    StringBuilder body = new StringBuilder();
    boolean fenced = false;
    for (String line : lines) {
      if (line.startsWith("```")) {
        fenced = !fenced;
      }
      if (!fenced && line.startsWith("## ")) {
        add(out, document, heading, body);
        heading = line.substring(3).trim();
        body.setLength(0);
        continue;
      }
      if (!fenced && line.startsWith("# ") && body.isEmpty() && heading.equals(title)) {
        continue;
      }
      body.append(line).append('\n');
    }
    add(out, document, heading, body);
    return out;
  }

  private static void add(List<Section> out, String document, String heading, StringBuilder body) {
    String text = body.toString().strip();
    if (!text.isEmpty()) {
      out.add(new Section(document, heading, text));
    }
  }

  /**
   * The keywords a question is searched for: lower case, stop words and short
   * words out, and an English ending taken off so "masked" finds "mask".
   */
  static List<String> terms(String query) {
    Set<String> out = new LinkedHashSet<>();
    if (query == null) {
      return List.of();
    }
    for (String word : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}\\p{M}_-]+")) {
      String w = word.replaceAll("^[-_]+|[-_]+$", "");
      boolean ascii = w.chars().allMatch(c -> c < 128);
      if (w.length() < (ascii ? 3 : 2) || STOP_WORDS.contains(w)) {
        continue;
      }
      out.add(ascii ? stem(w) : w);
      if (out.size() == MAX_TERMS) {
        break;
      }
    }
    return List.copyOf(out);
  }

  /**
   * A plain English ending off a word, so the stem is a substring of every
   * form: "changes" and "change" find "changing", "policy" finds "policies",
   * "tags" finds "tag". A word is never cut below three letters ("s") or four
   * (the rest), so "denied" does not become "den".
   */
  static String stem(String word) {
    String w = word;
    for (String ending : List.of("ies", "ied", "ing", "ed", "s")) {
      int keep = ending.equals("s") ? 3 : 4;
      if (w.endsWith(ending) && !w.endsWith("ss") && w.length() - ending.length() >= keep) {
        w = w.substring(0, w.length() - ending.length());
        break;
      }
    }
    if (w.length() >= 5 && (w.endsWith("e") || w.endsWith("y"))) {
      w = w.substring(0, w.length() - 1);
    }
    return w;
  }

  /**
   * The sections that answer a question best, as text for the model.
   *
   * <p>A keyword in a heading counts most; a keyword few sections have counts
   * more than one most of them have ("password" over "change"); and the more of
   * the keywords a section holds, the higher it ranks, so a section that
   * mentions one word many times does not beat one that is about the whole
   * question.
   */
  public String search(String query) {
    if (sections.isEmpty()) {
      return "error: the documentation is not packed in this build. Say you cannot look it up.";
    }
    List<String> terms = terms(query);
    if (terms.isEmpty()) {
      return "error: give one or more English keywords of three or more letters, such as"
          + " 'request access' or 'mask'.";
    }

    List<String> headings = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (Section s : sections) {
      headings.add(s.heading().toLowerCase(Locale.ROOT));
      texts.add(s.text().toLowerCase(Locale.ROOT));
    }
    double[] rarity = new double[terms.size()];
    for (int t = 0; t < terms.size(); t++) {
      int holding = 0;
      for (int i = 0; i < sections.size(); i++) {
        if (headings.get(i).contains(terms.get(t)) || texts.get(i).contains(terms.get(t))) {
          holding++;
        }
      }
      rarity[t] = Math.log(1.0 + (double) sections.size() / Math.max(1, holding));
    }

    record Hit(Section section, double score, int order) {}
    List<Hit> hits = new ArrayList<>();
    for (int i = 0; i < sections.size(); i++) {
      double score = 0;
      int matched = 0;
      for (int t = 0; t < terms.size(); t++) {
        int inHeading = headings.get(i).contains(terms.get(t)) ? 1 : 0;
        int inText = Math.min(count(texts.get(i), terms.get(t)), 5);
        if (inHeading + inText > 0) {
          matched++;
          score += rarity[t] * (inHeading * 6 + inText);
        }
      }
      if (matched > 0) {
        hits.add(new Hit(sections.get(i), score * matched, i));
      }
    }
    if (hits.isEmpty()) {
      StringBuilder out =
          new StringBuilder("Nothing in the ARAK documentation matches ")
              .append(String.join(", ", terms))
              .append(". Try other English words, or one of these section headings; if it is")
              .append(" still not there, say the guide does not cover it:\n");
      sections.stream()
          .filter(s -> s.document().contains("user-guide.md"))
          .forEach(s -> out.append("- ").append(s.heading()).append('\n'));
      return out.toString().trim();
    }

    hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(Hit::order));
    StringBuilder out = new StringBuilder();
    for (Hit hit : hits.subList(0, Math.min(MAX_SECTIONS, hits.size()))) {
      Section s = hit.section();
      out.append("From ")
          .append(s.document())
          .append(", section \"")
          .append(s.heading())
          .append("\":\n")
          .append(AgentPrompts.cap(s.text(), MAX_SECTION))
          .append("\n\n");
    }
    return out.toString().trim();
  }

  private static int count(String text, String term) {
    int n = 0;
    for (int at = text.indexOf(term); at >= 0; at = text.indexOf(term, at + term.length())) {
      n++;
    }
    return n;
  }
}
