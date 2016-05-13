// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.flows;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.Sets.difference;
import static com.google.common.truth.Truth.assertThat;
import static com.google.domain.registry.flows.EppXmlTransformer.marshal;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.testing.DatastoreHelper.BILLING_EVENT_ID_STRIPPER;
import static com.google.domain.registry.testing.DatastoreHelper.getPollMessages;
import static com.google.domain.registry.util.ResourceUtils.readResourceUtf8;
import static com.google.domain.registry.xml.XmlTestUtils.assertXmlEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static org.joda.time.DateTimeZone.UTC;

import com.google.common.base.Function;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.domain.registry.flows.FlowRunner.CommitMode;
import com.google.domain.registry.flows.FlowRunner.UserPrivileges;
import com.google.domain.registry.flows.SessionMetadata.SessionSource;
import com.google.domain.registry.model.billing.BillingEvent;
import com.google.domain.registry.model.domain.GracePeriod;
import com.google.domain.registry.model.domain.rgp.GracePeriodStatus;
import com.google.domain.registry.model.eppcommon.ProtocolDefinition;
import com.google.domain.registry.model.eppcommon.Trid;
import com.google.domain.registry.model.eppinput.EppInput;
import com.google.domain.registry.model.eppoutput.EppOutput;
import com.google.domain.registry.model.ofy.Ofy;
import com.google.domain.registry.model.poll.PollMessage;
import com.google.domain.registry.model.reporting.HistoryEntry;
import com.google.domain.registry.model.tmch.ClaimsListShard.ClaimsListSingleton;
import com.google.domain.registry.testing.AppEngineRule;
import com.google.domain.registry.testing.EppLoader;
import com.google.domain.registry.testing.FakeClock;
import com.google.domain.registry.testing.InjectRule;
import com.google.domain.registry.testing.TestSessionMetadata;
import com.google.domain.registry.util.TypeUtils.TypeInstantiator;
import com.google.domain.registry.xml.ValidationMode;

import org.joda.time.DateTime;
import org.junit.Before;
import org.junit.Rule;
import org.junit.runner.RunWith;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

/**
 * Base class for resource flow unit tests.
 *
 * @param <F> the flow type
 */
@RunWith(MockitoJUnitRunner.class)
public abstract class FlowTestCase<F extends Flow> {

  @Rule
  public final AppEngineRule appEngine = AppEngineRule.builder()
      .withDatastore()
      .withTaskQueue()
      .build();

  @Rule
  public final InjectRule inject = new InjectRule();

  private FlowRunner flowRunner;

  protected EppLoader eppLoader;
  protected Class<? extends Flow> flowClass;
  protected SessionMetadata sessionMetadata;
  protected FakeClock clock = new FakeClock(DateTime.now(UTC));

  @Before
  public void init() throws Exception {
    flowRunner = null;
    sessionMetadata = new TestSessionMetadata();
    sessionMetadata.setClientId("TheRegistrar");
    sessionMetadata.setServiceExtensionUris(ProtocolDefinition.getVisibleServiceExtensionUris());
    sessionMetadata.setSessionSource(SessionSource.NONE);
    ofy().saveWithoutBackup().entity(new ClaimsListSingleton()).now();
    inject.setStaticField(Ofy.class, "clock", clock);  // For transactional flows.
    inject.setStaticField(FlowRunner.class, "clock", clock);  // For non-transactional flows.
  }

  protected void removeServiceExtensionUri(String uri) {
    sessionMetadata.setServiceExtensionUris(
        difference(sessionMetadata.getServiceExtensionUris(), ImmutableSet.of(uri)));
  }

  protected void setEppInput(String inputFilename) {
    eppLoader = new EppLoader(this, inputFilename, ImmutableMap.<String, String>of());
  }

  protected void setEppInput(String inputFilename, Map<String, String> substitutions) {
    eppLoader = new EppLoader(this, inputFilename, substitutions);
  }

  protected String readFile(String filename) {
    return readResourceUtf8(getClass(), "testdata/" + filename);
  }

  /** Lazily load the flow, since it may fail to initialize if the environment isn't set up yet. */
  public FlowRunner getFlowRunner() throws Exception {
    if (flowRunner == null) {
      flowRunner = createFlowRunner();
    }
    return flowRunner;
  }

  /** Load a flow from an epp object. */
  private FlowRunner createFlowRunner() throws Exception {
    EppInput eppInput = eppLoader.getEpp();
    flowClass = firstNonNull(flowClass, FlowRegistry.getFlowClass(eppInput));
    Class<?> expectedFlowClass = new TypeInstantiator<F>(getClass()){}.getExactType();
    assertThat(flowClass).isEqualTo(expectedFlowClass);
    return new FlowRunner(
        flowClass,
        eppInput,
        getTrid(),
        sessionMetadata,
        "<xml></xml>".getBytes(),
        null);
  }

  protected Trid getTrid() throws Exception {
    return Trid.create(eppLoader.getEpp().getCommandWrapper().getClTrid(), "server-trid");
  }

  /** Gets the client ID that the flow will run as. */
  protected String getClientIdForFlow() {
    return sessionMetadata.getClientId();
  }

  /** Sets the client ID that the flow will run as. */
  protected void setClientIdForFlow(String clientId) {
    sessionMetadata.setClientId(clientId);
  }

  public void assertTransactionalFlow(boolean isTransactional) throws Exception {
    assertThat(getFlowRunner().isTransactional()).isEqualTo(isTransactional);
  }

  public void assertNoHistory() throws Exception {
    assertThat(ofy().load().type(HistoryEntry.class)).isEmpty();
  }

  public <T> T getOnlyGlobalResource(Class<T> clazz) throws Exception {
    return Iterables.getOnlyElement(ofy().load().type(clazz));
  }

  /** Helper to remove the grace period's billing event key to facilitate comparison. */
  private static final Function<GracePeriod, GracePeriod> GRACE_PERIOD_KEY_STRIPPER =
      new Function<GracePeriod, GracePeriod>() {
        @Override
        public GracePeriod apply(GracePeriod gracePeriod) {
          return GracePeriod.create(
              gracePeriod.isSunrushAddGracePeriod()
                  ? GracePeriodStatus.SUNRUSH_ADD
                  : gracePeriod.getType(),
              gracePeriod.getExpirationTime(),
              gracePeriod.getClientId(),
              null);
        }};

  /** A helper class that sets the billing event parent history entry to facilitate comparison. */
  public static class BillingEventParentSetter implements Function<BillingEvent, BillingEvent> {
    private HistoryEntry historyEntry;

    public static BillingEventParentSetter withParent(HistoryEntry historyEntry) {
      BillingEventParentSetter instance = new BillingEventParentSetter();
      instance.historyEntry = historyEntry;
      return instance;
    }

    @Override
    public BillingEvent apply(BillingEvent billingEvent) {
      return billingEvent.asBuilder().setParent(historyEntry).build();
    }

    private BillingEventParentSetter() {}
  }

  /**
   * Helper to facilitate comparison of maps of GracePeriods to BillingEvents.  This takes a map of
   * GracePeriods to BillingEvents and returns a map of the same entries that ignores the keys
   * on the grace periods and the IDs on the billing events (by setting them all to the same dummy
   * values), since they will vary between instantiations even when the other data is the same.
   */
  private ImmutableMap<GracePeriod, BillingEvent>
      canonicalizeGracePeriods(ImmutableMap<GracePeriod, ? extends BillingEvent> gracePeriods) {
    ImmutableMap.Builder<GracePeriod, BillingEvent> builder = new ImmutableMap.Builder<>();
    for (Map.Entry<GracePeriod, ? extends BillingEvent> entry : gracePeriods.entrySet()) {
      builder.put(
          GRACE_PERIOD_KEY_STRIPPER.apply(entry.getKey()),
          BILLING_EVENT_ID_STRIPPER.apply(entry.getValue()));
    }
    return builder.build();
  }

  /**
   * Assert that the actual grace periods and the corresponding billing events referenced from
   * their keys match the expected map of grace periods to billing events.  For the expected map,
   * the keys on the grace periods and IDs on the billing events are ignored.
   */
  public void assertGracePeriods(
      Iterable<GracePeriod> actual,
      ImmutableMap<GracePeriod, ? extends BillingEvent> expected) {
    Function<GracePeriod, BillingEvent> gracePeriodExpander =
        new Function<GracePeriod, BillingEvent>() {
          @Override
          public BillingEvent apply(GracePeriod gracePeriod) {
            assertThat(gracePeriod.hasBillingEvent())
                .named("Billing event is present for grace period: " + gracePeriod)
                .isTrue();
            return firstNonNull(
                gracePeriod.getOneTimeBillingEvent(),
                gracePeriod.getRecurringBillingEvent())
                    .get();
          }};
    assertThat(canonicalizeGracePeriods(Maps.toMap(actual, gracePeriodExpander)))
        .isEqualTo(canonicalizeGracePeriods(expected));
  }

  public void assertPollMessages(
      String clientId,
      PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(clientId), expected);
  }

  public void assertPollMessages(
      String clientId,
      DateTime now,
      PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(clientId, now), expected);
  }

  public void assertPollMessages(PollMessage... expected) throws Exception {
    assertPollMessagesHelper(getPollMessages(), expected);
  }

  /** Assert that the list matches all the poll messages in the fake datastore. */
  public void assertPollMessagesHelper(Iterable<PollMessage> pollMessages, PollMessage... expected)
      throws Exception {
    // To facilitate comparison, remove the ids.
    Function<PollMessage, PollMessage> idStripper =
        new Function<PollMessage, PollMessage>() {
          @Override
          public PollMessage apply(PollMessage pollMessage) {
            return pollMessage.asBuilder().setId(1L).build();
          }};
    // Ordering is irrelevant but duplicates should be considered independently.
    assertThat(FluentIterable.from(pollMessages).transform(idStripper))
        .containsExactlyElementsIn(FluentIterable.from(asList(expected)).transform(idStripper));
  }

  /** Run a flow, and attempt to marshal the result to EPP or throw if it doesn't validate. */
  public EppOutput runFlow(CommitMode commitMode, UserPrivileges userPrivileges) throws Exception {
    EppOutput output = getFlowRunner().run(commitMode, userPrivileges);
    marshal(output, ValidationMode.STRICT);
    return output;
  }

  public EppOutput runFlow() throws Exception {
    return runFlow(CommitMode.LIVE, UserPrivileges.NORMAL);
  }

  public void runFlowAssertResponse(
      CommitMode commitMode, UserPrivileges userPrivileges, String xml, String... ignoredPaths)
      throws Exception {
    EppOutput eppOutput = getFlowRunner().run(commitMode, userPrivileges);
    assertThat(eppOutput.isSuccess()).isTrue();
    try {
      assertXmlEquals(
          xml, new String(marshal(eppOutput, ValidationMode.STRICT), UTF_8), ignoredPaths);
    } catch (Throwable e) {
      assertXmlEquals(
          xml, new String(marshal(eppOutput, ValidationMode.LENIENT), UTF_8), ignoredPaths);
      // If it was a marshaling error, augment the output.
      throw new Exception(
          String.format(
              "Invalid xml.\nExpected:\n%s\n\nActual:\n%s\n",
              xml,
              marshal(eppOutput, ValidationMode.LENIENT)),
          e);
    }
    // Clear the cache so that we don't see stale results in tests.
    ofy().clearSessionCache();
  }

  public void dryRunFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    List<Object> beforeEntities = ofy().load().list();
    runFlowAssertResponse(CommitMode.DRY_RUN, UserPrivileges.NORMAL, xml, ignoredPaths);
    assertThat(ofy().load()).containsExactlyElementsIn(beforeEntities);
  }

  public void runFlowAssertResponse(String xml, String... ignoredPaths) throws Exception {
    runFlowAssertResponse(CommitMode.LIVE, UserPrivileges.NORMAL, xml, ignoredPaths);
  }
}