package com.mfec.dac.enforcement;

import com.mfec.dac.source.DataSourceStore;
import com.mfec.dac.source.jdbc.CredentialResolver;
import com.mfec.dac.source.jdbc.DirectAccessReader;
import com.mfec.dac.source.jdbc.SourceProbe;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether a table the platform is meant to guard can still be read around it
 * (FR-6.3.1).
 *
 * <p>The proxy and the secure view are only as good as the door they leave
 * shut. A source set to {@code PROXY} whose table any analyst can still select
 * from with their own login is not governed; it is governed for the people who
 * happen to use the platform. This asks the source who holds the table and
 * says which of them are not the platform.
 *
 * <p>What "guarded" means here: the source's mode is {@code PROXY} or
 * {@code SECURE_VIEW}, or a secure view is installed on this table whatever the
 * mode says. Under either, anybody but the platform's own login holding the
 * base table is a way around the policies. Under {@code NATIVE_CONFIG} and
 * {@code NONE} people are meant to read the table directly, so the same list is
 * only information: who reads it, and by what grant.
 *
 * <p>Every check is written to the enforcement trail, whatever it finds,
 * because it opens a connection to a customer's database.
 */
public final class DirectAccessService {

  private static final Logger LOG = LoggerFactory.getLogger(DirectAccessService.class);

  static final String ACTION = "DIRECT_ACCESS_CHECK";

  /** How the check reads the source; an interface so tests need no database. */
  public interface Reader {
    DirectAccessReader.Report read(
        SourceProbe.Target target, String credentialRef, String schema, String table)
        throws SQLException, CredentialResolver.UnresolvableCredentialException;
  }

  /**
   * {@code EXPOSED}: guarded, and somebody else holds the table. {@code CLOSED}:
   * guarded, and only the platform does. {@code OPEN}: not guarded, so direct
   * reads are how the table is meant to be reached. {@code NOT_FOUND}: the
   * source has no table where the catalogue places it.
   */
  public enum Verdict {
    EXPOSED,
    CLOSED,
    OPEN,
    NOT_FOUND
  }

  /**
   * @param guarded whether the mode relies on the base table being shut
   * @param bypass how many holders are not the platform's own login
   */
  public record Check(
      String assetFqn,
      String source,
      String engine,
      String mode,
      boolean secureViewInstalled,
      boolean guarded,
      String schema,
      String table,
      String connectedAs,
      List<DirectAccessReader.Holder> holders,
      int bypass,
      Verdict verdict,
      String message,
      Instant checkedAt,
      long millis) {}

  private final SecureViewService views;
  private final Reader reader;
  private final EnforcementStateStore states;
  private final Clock clock;

  public DirectAccessService(
      SecureViewService views, Reader reader, EnforcementStateStore states, Clock clock) {
    this.views = views;
    this.reader = reader;
    this.states = states;
    this.clock = clock;
  }

  public Check check(String assetFqn, String actor, String clientIp) {
    SecureViewService.Located at = null;
    try {
      at = views.locate(assetFqn);
      DataSourceStore.Source source = at.source();
      String mode = modeOf(source);
      boolean installed =
          views.state(at.fqn()).map(EnforcementStateStore.State::isInstalled).orElse(false);
      boolean guarded = installed || "PROXY".equals(mode) || "SECURE_VIEW".equals(mode);

      DirectAccessReader.Report report;
      try {
        report =
            reader.read(
                new SourceProbe.Target(
                    source.engine().name(), source.host(), source.port(), source.defaultDatabase()),
                source.credentialRef(),
                at.schema(),
                at.table());
      } catch (CredentialResolver.UnresolvableCredentialException e) {
        throw new SecureViewService.RefusedException(e.getMessage());
      } catch (IllegalArgumentException e) {
        throw new SecureViewService.RefusedException(e.getMessage());
      } catch (SQLException e) {
        LOG.warn("Direct access on {}: could not read the source: {}", at.fqn(), e.toString());
        throw new SecureViewService.SourceFailureException(
            source.name() + " refused to say who holds " + at.schema() + "." + at.table() + ": "
                + e.getMessage(),
            e);
      }

      int bypass = (int) report.holders().stream().filter(holder -> !holder.self()).count();
      Verdict verdict;
      String message;
      String where = at.schema() + "." + at.table();
      if (!report.found()) {
        verdict = Verdict.NOT_FOUND;
        message =
            source.name() + " has no table " + where
                + "; the catalogue's mapping is out of date, so nothing can be said about it.";
      } else if (!guarded) {
        verdict = Verdict.OPEN;
        message =
            "The mode here is " + mode + ", so people are meant to read " + where
                + " directly. " + bypass + (bypass == 1 ? " principal holds" : " principals hold")
                + " it besides the platform.";
      } else if (bypass > 0) {
        verdict = Verdict.EXPOSED;
        message =
            bypass + (bypass == 1 ? " principal" : " principals")
                + " can read " + where + " without the platform, so the policies here do not"
                + " reach them. Revoke their access at the source, or change the mode.";
      } else {
        verdict = Verdict.CLOSED;
        message = "Only the platform's own login can read " + where + " at the source.";
      }

      String detail =
          verdict + ": " + report.holders().size() + " holders, " + bypass + " outside the platform";
      audit(assetFqn, at, mode, "CHECKED", detail, actor, clientIp);
      return new Check(
          at.fqn(),
          source.name(),
          source.engine().name(),
          mode,
          installed,
          guarded,
          at.schema(),
          at.table(),
          report.connectedAs(),
          report.holders(),
          bypass,
          verdict,
          message,
          clock.instant(),
          report.millis());
    } catch (SecureViewService.NotFoundException e) {
      throw e;
    } catch (SecureViewService.RefusedException | SecureViewService.SourceFailureException e) {
      audit(
          assetFqn,
          at,
          at == null ? null : modeOf(at.source()),
          e instanceof SecureViewService.SourceFailureException ? "FAILED" : "REFUSED",
          e.getMessage(),
          actor,
          clientIp);
      throw e;
    }
  }

  private static String modeOf(DataSourceStore.Source source) {
    return source.defaultEnforcementMode() == null
        ? DataSourceStore.EnforcementMode.NONE.name()
        : source.defaultEnforcementMode().name();
  }

  private void audit(
      String assetFqn,
      SecureViewService.Located at,
      String mode,
      String outcome,
      String detail,
      String actor,
      String clientIp) {
    try {
      states.audit(
          new EnforcementStateStore.Audit(
              actor,
              at == null ? String.valueOf(assetFqn) : at.fqn(),
              at == null ? null : at.source().id(),
              mode == null ? DataSourceStore.EnforcementMode.NONE.name() : mode,
              ACTION,
              outcome,
              null,
              null,
              null,
              null,
              null,
              detail,
              clientIp));
    } catch (RuntimeException failure) {
      LOG.error("Could not write the enforcement audit trail for {}", assetFqn, failure);
    }
  }
}
