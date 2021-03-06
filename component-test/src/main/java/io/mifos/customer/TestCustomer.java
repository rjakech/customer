/*
 * Copyright 2017 The Mifos Initiative
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mifos.customer;

import io.mifos.anubis.test.v1.TenantApplicationSecurityEnvironmentTestRule;
import io.mifos.core.api.context.AutoUserContext;
import io.mifos.core.test.env.TestEnvironment;
import io.mifos.core.test.fixture.TenantDataStoreContextTestRule;
import io.mifos.core.test.fixture.cassandra.CassandraInitializer;
import io.mifos.core.test.fixture.mariadb.MariaDBInitializer;
import io.mifos.core.test.listener.EnableEventRecording;
import io.mifos.core.test.listener.EventRecorder;
import io.mifos.customer.api.v1.CustomerEventConstants;
import io.mifos.customer.api.v1.client.CustomerAlreadyExistsException;
import io.mifos.customer.api.v1.client.CustomerManager;
import io.mifos.customer.api.v1.client.CustomerNotFoundException;
import io.mifos.customer.api.v1.client.CustomerValidationException;
import io.mifos.customer.api.v1.domain.Address;
import io.mifos.customer.api.v1.domain.Command;
import io.mifos.customer.api.v1.domain.ContactDetail;
import io.mifos.customer.api.v1.domain.Customer;
import io.mifos.customer.api.v1.domain.CustomerPage;
import io.mifos.customer.api.v1.domain.IdentificationCard;
import io.mifos.customer.service.rest.config.CustomerRestConfiguration;
import io.mifos.customer.util.AddressGenerator;
import io.mifos.customer.util.CommandGenerator;
import io.mifos.customer.util.ContactDetailGenerator;
import io.mifos.customer.util.CustomerGenerator;
import io.mifos.customer.util.IdentificationCardGenerator;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class TestCustomer {

  private static final String APP_NAME = "customer-v1";

  @Configuration
  @EnableEventRecording
  @EnableFeignClients(basePackages = {"io.mifos.customer.api.v1.client"})
  @RibbonClient(name = APP_NAME)
  @ComponentScan(
      basePackages = {
            "io.mifos.customer.listener"
      }
  )
  @Import({CustomerRestConfiguration.class})
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }
  }
  private static final String TEST_USER = "maatkare";
  private final static TestEnvironment testEnvironment = new TestEnvironment(APP_NAME);
  private final static CassandraInitializer cassandraInitializer = new CassandraInitializer();
  private final static MariaDBInitializer mariaDBInitializer = new MariaDBInitializer();
  private final static TenantDataStoreContextTestRule tenantDataStoreContext = TenantDataStoreContextTestRule.forRandomTenantName(cassandraInitializer, mariaDBInitializer);

  @ClassRule
  public static TestRule orderClassRules = RuleChain
          .outerRule(testEnvironment)
          .around(cassandraInitializer)
          .around(mariaDBInitializer)
          .around(tenantDataStoreContext);

  @Rule
  public final TenantApplicationSecurityEnvironmentTestRule tenantApplicationSecurityEnvironment
          = new TenantApplicationSecurityEnvironmentTestRule(testEnvironment, this::waitForInitialize);

  @Autowired
  private CustomerManager customerManager;

  @Autowired
  private EventRecorder eventRecorder;

  private AutoUserContext userContext;

  public TestCustomer() {
    super();
  }

  @Before
  public void prepareTest() {
    userContext = tenantApplicationSecurityEnvironment.createAutoUserContext(TEST_USER);
  }

  @After
  public void cleanupTest() {
    userContext.close();
  }

  public boolean waitForInitialize() {
    try {
      return this.eventRecorder.wait(CustomerEventConstants.INITIALIZE, CustomerEventConstants.INITIALIZE);
    } catch (final InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void shouldCreateCustomer() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    final Customer createdCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertNotNull(createdCustomer);
  }

  @Test
  public void shouldNotCreateCustomerAlreadyExists() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    try {
      this.customerManager.createCustomer(customer);
      Assert.fail();
    } catch (final CustomerAlreadyExistsException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotCreateCustomerValidationFailed() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    customer.getAddress().setStreet(null);
    customer.setContactDetails(null);

    try {
      this.customerManager.createCustomer(customer);
      Assert.fail();
    } catch (final CustomerValidationException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldFindCustomer() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    final Customer foundCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertNotNull(foundCustomer);
    Assert.assertNotNull(foundCustomer.getIdentificationCard());
    Assert.assertNotNull(foundCustomer.getAddress());
    Assert.assertNotNull(foundCustomer.getContactDetails());
    Assert.assertEquals(2, foundCustomer.getContactDetails().size());
  }

  @Test
  public void shouldNotFindCustomerNotFound() throws Exception {
    try {
      this.customerManager.findCustomer(RandomStringUtils.randomAlphanumeric(8));
      Assert.fail();
    } catch (final CustomerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldFetchCustomers() throws Exception {
    Stream.of(
        CustomerGenerator.createRandomCustomer(),
        CustomerGenerator.createRandomCustomer(),
        CustomerGenerator.createRandomCustomer()
    ).forEach(customer -> {
      this.customerManager.createCustomer(customer);
      try {
        this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());
      } catch (final InterruptedException ex) {
        Assert.fail(ex.getMessage());
      }
    });

    final CustomerPage customerPage = this.customerManager.fetchCustomers(null, null, 0, 20, null, null);
    Assert.assertTrue(customerPage.getTotalElements() >= 3);
  }


  @Test
  public void shouldFetchCustomersByTerm() throws Exception {
    final Customer randomCustomer = CustomerGenerator.createRandomCustomer();
    final String randomCustomerIdentifier = randomCustomer.getIdentifier();
    this.customerManager.createCustomer(randomCustomer);
    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, randomCustomerIdentifier);

    final CustomerPage customerPage = this.customerManager.fetchCustomers(randomCustomerIdentifier, Boolean.FALSE, 0, 20, null, null);
    Assert.assertTrue(customerPage.getTotalElements() == 1);
  }

  @Test
  public void shouldUpdateCustomer() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    customer.setSurname(RandomStringUtils.randomAlphanumeric(256));

    this.customerManager.updateCustomer(customer.getIdentifier(), customer);

    this.eventRecorder.wait(CustomerEventConstants.PUT_CUSTOMER, customer.getIdentifier());

    final Customer updatedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(customer.getSurname(), updatedCustomer.getSurname());
  }

  @Test
  public void shouldNotUpdateCustomerNotFound() throws Exception {
    try {
      this.customerManager.updateCustomer(RandomStringUtils.randomAlphanumeric(8), CustomerGenerator.createRandomCustomer());
      Assert.fail();
    } catch (final CustomerNotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldActivateClient() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    final Customer activatedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(Customer.State.ACTIVE.name(), activatedCustomer.getCurrentState());
  }

  @Test
  public void shouldLockClient() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.LOCK, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.LOCK_CUSTOMER, customer.getIdentifier());

    final Customer lockedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(Customer.State.LOCKED.name(), lockedCustomer.getCurrentState());
  }

  @Test
  public void shouldUnlockClient() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.LOCK, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.LOCK_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.UNLOCK, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.UNLOCK_CUSTOMER, customer.getIdentifier());

    final Customer unlockedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(Customer.State.ACTIVE.name(), unlockedCustomer.getCurrentState());
  }

  @Test
  public void shouldCloseClient() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.CLOSE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.CLOSE_CUSTOMER, customer.getIdentifier());

    final Customer closedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(Customer.State.CLOSED.name(), closedCustomer.getCurrentState());
  }

  @Test
  public void shouldReopenClient() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.CLOSE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.CLOSE_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.REOPEN, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.REOPEN_CUSTOMER, customer.getIdentifier());

    final Customer reopenedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    Assert.assertEquals(Customer.State.ACTIVE.name(), reopenedCustomer.getCurrentState());
  }

  @Test
  public void shouldFetchCommands() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    this.customerManager.customerCommand(customer.getIdentifier(), CommandGenerator.create(Command.Action.ACTIVATE, "Test"));
    this.eventRecorder.wait(CustomerEventConstants.ACTIVATE_CUSTOMER, customer.getIdentifier());

    final List<Command> commands = this.customerManager.fetchCustomerCommands(customer.getIdentifier());
    Assert.assertTrue(commands.size() == 1);
  }

  @Test
  public void shouldUpdateAddress() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    final Address address = AddressGenerator.createRandomAddress();
    this.customerManager.putAddress(customer.getIdentifier(), address);

    this.eventRecorder.wait(CustomerEventConstants.PUT_ADDRESS, customer.getIdentifier());

    final Customer changedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    final Address changedAddress = changedCustomer.getAddress();

    Assert.assertEquals(address.getCity(), changedAddress.getCity());
    Assert.assertEquals(address.getCountryCode(), changedAddress.getCountryCode());
    Assert.assertEquals(address.getPostalCode(), changedAddress.getPostalCode());
    Assert.assertEquals(address.getRegion(), changedAddress.getRegion());
    Assert.assertEquals(address.getStreet(), changedAddress.getStreet());
    Assert.assertEquals(address.getCountry(), changedAddress.getCountry());
  }

  @Test
  public void shouldUpdateContactDetails() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    final ContactDetail contactDetail = ContactDetailGenerator.createRandomContactDetail();
    this.customerManager.putContactDetails(customer.getIdentifier(), Collections.singletonList(contactDetail));

    this.eventRecorder.wait(CustomerEventConstants.PUT_CONTACT_DETAILS, customer.getIdentifier());

    final Customer changedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    final List<ContactDetail> changedContactDetails = changedCustomer.getContactDetails();
    Assert.assertEquals(1, changedContactDetails.size());
    final ContactDetail changedContactDetail = changedContactDetails.get(0);
    Assert.assertEquals(contactDetail.getType(), changedContactDetail.getType());
    Assert.assertEquals(contactDetail.getValue(), changedContactDetail.getValue());
    Assert.assertEquals(contactDetail.getValidated(), changedContactDetail.getValidated());
    Assert.assertEquals(contactDetail.getGroup(), changedContactDetail.getGroup());
    Assert.assertEquals(contactDetail.getPreferenceLevel(), changedContactDetail.getPreferenceLevel());
  }

  @Test
  public void shouldUpdateIdentificationCard() throws Exception {
    final Customer customer = CustomerGenerator.createRandomCustomer();
    this.customerManager.createCustomer(customer);

    this.eventRecorder.wait(CustomerEventConstants.POST_CUSTOMER, customer.getIdentifier());

    final IdentificationCard newIdentificationCard = IdentificationCardGenerator.createRandomIdentificationCard();
    this.customerManager.putIdentificationCard(customer.getIdentifier(), newIdentificationCard);

    this.eventRecorder.wait(CustomerEventConstants.PUT_IDENTIFICATION_CARD, customer.getIdentifier());

    final Customer changedCustomer = this.customerManager.findCustomer(customer.getIdentifier());
    final IdentificationCard changedIdentificationCard = changedCustomer.getIdentificationCard();
    Assert.assertNotNull(changedIdentificationCard);
    Assert.assertEquals(newIdentificationCard.getType(), changedIdentificationCard.getType());
    Assert.assertEquals(newIdentificationCard.getIssuer(), changedIdentificationCard.getIssuer());
    Assert.assertEquals(newIdentificationCard.getNumber(), changedIdentificationCard.getNumber());
  }
}
