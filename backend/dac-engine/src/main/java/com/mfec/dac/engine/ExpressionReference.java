package com.mfec.dac.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The documentation for the expression language, as data rather than prose.
 *
 * <p>The page a policy author reads and the parser they are writing against are
 * two descriptions of one language, and the interesting failure is not that the
 * page is missing — it is that the page is confidently wrong after somebody
 * changes the grammar. So the examples are not written on the page. They live
 * here beside the parser, every one of them carries the principal, asset and
 * answer it claims, and {@code ExpressionReferenceTest} runs the lot through
 * {@link PolicyExpressionEvaluator}. An example that stops being true fails the
 * build rather than quietly misleading whoever reads it next.
 *
 * <p>The front end is served this same document, so the page cannot describe a
 * language the deployed engine is not running either.
 */
public final class ExpressionReference {

  private static final String RESOURCE = "/expression/reference.json";

  private static final JsonNode DOCUMENT = load();

  private ExpressionReference() {}

  /** The reference document: roots, operators, worked examples, rejections. */
  public static JsonNode document() {
    return DOCUMENT;
  }

  private static JsonNode load() {
    try (InputStream in = ExpressionReference.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the jar");
      }
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + RESOURCE, e);
    }
  }
}
