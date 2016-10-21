/*************************************************************************
 * (c) Copyright 2016 Hewlett Packard Enterprise Development Company LP
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 *  This file may incorporate work covered under the following copyright and permission notice:
 *
 *   Copyright 2010-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License").
 *   You may not use this file except in compliance with the License.
 *   A copy of the License is located at
 *
 *    http://aws.amazon.com/apache2.0
 *
 *   or in the "license" file accompanying this file. This file is distributed
 *   on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *   express or implied. See the License for the specific language governing
 *   permissions and limitations under the License.
 ************************************************************************/

package com.eucalyptus.simplequeue;

import com.amazonaws.util.BinaryUtils;
import com.amazonaws.util.Md5Utils;
import com.eucalyptus.auth.AuthException;
import com.eucalyptus.auth.Permissions;
import com.eucalyptus.auth.PolicyParseException;
import com.eucalyptus.auth.euare.Accounts;
import com.eucalyptus.auth.euare.identity.region.RegionConfigurations;
import com.eucalyptus.auth.policy.PolicyParser;
import com.eucalyptus.auth.policy.ern.Ern;
import com.eucalyptus.component.ServiceUris;
import com.eucalyptus.component.Topology;
import com.eucalyptus.component.annotation.ComponentNamed;
import com.eucalyptus.configurable.ConfigurableClass;
import com.eucalyptus.configurable.ConfigurableField;
import com.eucalyptus.configurable.ConfigurableProperty;
import com.eucalyptus.configurable.ConfigurablePropertyException;
import com.eucalyptus.configurable.PropertyChangeListener;
import com.eucalyptus.configurable.PropertyChangeListeners;
import com.eucalyptus.context.Context;
import com.eucalyptus.context.Contexts;
import com.eucalyptus.simplequeue.common.policy.SimpleQueuePolicySpec;
import com.eucalyptus.simplequeue.exceptions.AccessDeniedException;
import com.eucalyptus.simplequeue.exceptions.BatchEntryIdsNotDistinctException;
import com.eucalyptus.simplequeue.exceptions.EmptyBatchRequestException;
import com.eucalyptus.simplequeue.exceptions.InternalFailureException;
import com.eucalyptus.simplequeue.exceptions.InvalidAddressException;
import com.eucalyptus.simplequeue.exceptions.InvalidAttributeNameException;
import com.eucalyptus.simplequeue.exceptions.InvalidBatchEntryIdException;
import com.eucalyptus.simplequeue.exceptions.InvalidParameterValueException;
import com.eucalyptus.simplequeue.exceptions.MissingParameterException;
import com.eucalyptus.simplequeue.exceptions.QueueAlreadyExistsException;
import com.eucalyptus.simplequeue.exceptions.QueueDoesNotExistException;
import com.eucalyptus.simplequeue.exceptions.SimpleQueueException;
import com.eucalyptus.simplequeue.exceptions.TooManyEntriesInBatchRequestException;
import com.eucalyptus.simplequeue.exceptions.UnsupportedOperationException;
import com.eucalyptus.simplequeue.persistence.PersistenceFactory;
import com.eucalyptus.simplequeue.persistence.Queue;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.util.Exceptions;
import com.eucalyptus.util.RestrictedTypes;
import com.eucalyptus.ws.Role;
import com.eucalyptus.ws.WebServices;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.RangeSet;
import com.google.common.collect.Sets;
import net.sf.json.JSONException;
import org.apache.log4j.Logger;
import org.apache.xml.security.exceptions.Base64DecodingException;
import org.apache.xml.security.utils.Base64;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ConfigurableClass( root = "services.simplequeue", description = "Parameters controlling simple queue (SQS)")

@ComponentNamed
public class SimpleQueueService {

  @ConfigurableField( description = "Maximum number of characters in a queue name.",
    initial = "80", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_QUEUE_NAME_LENGTH_CHARS = 80;

  @ConfigurableField( description = "Maximum number of characters in a label.",
    initial = "80", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_LABEL_LENGTH_CHARS = 80;

  @ConfigurableField( description = "Maximum value for delay seconds.",
    initial = "900", changeListener = WebServices.CheckNonNegativeLongPropertyChangeListener.class )
  public volatile static int MAX_DELAY_SECONDS = 900;

  @ConfigurableField( description = "Maximum value for maximum message size.",
    initial = "262144", changeListener = CheckMin1024IntPropertyChangeListener.class )
  public volatile static int MAX_MAXIMUM_MESSAGE_SIZE = 262144;

  @ConfigurableField( description = "Maximum value for message retention period.",
    initial = "1209600", changeListener = CheckMin60IntPropertyChangeListener.class )
  public volatile static int MAX_MESSAGE_RETENTION_PERIOD = 1209600;

  @ConfigurableField( description = "Maximum value for receive message wait time seconds.",
    initial = "20", changeListener = WebServices.CheckNonNegativeLongPropertyChangeListener.class )
  public volatile static int MAX_RECEIVE_MESSAGE_WAIT_TIME_SECONDS = 20;

  @ConfigurableField( description = "Maximum value for visibility timeout.",
    initial = "43200", changeListener = WebServices.CheckNonNegativeLongPropertyChangeListener.class )
  public volatile static int MAX_VISIBILITY_TIMEOUT = 43200;

  @ConfigurableField( description = "Maximum value for maxReceiveCount (dead letter queue).",
    initial = "1000", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_MAX_RECEIVE_COUNT = 1000;

  @ConfigurableField( description = "Maximum value for maxNumberOfMessages (ReceiveMessages).",
    initial = "10", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_RECEIVE_MESSAGE_MAX_NUMBER_OF_MESSAGES = 10;

  @ConfigurableField( description = "Maximum length of message attribute name. (chars)",
    initial = "256", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_MESSAGE_ATTRIBUTE_NAME_LENGTH = 256;

  @ConfigurableField( description = "Maximum number of bytes in message attribute type. (bytes)",
    initial = "256", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_MESSAGE_ATTRIBUTE_TYPE_LENGTH = 256;

  @ConfigurableField( description = "Maximum number of entries in a batch request",
    initial = "10", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_NUM_BATCH_ENTRIES = 10;

  @ConfigurableField( description = "Maximum length of batch id. (chars)",
    initial = "80", changeListener = PropertyChangeListeners.IsPositiveInteger.class )
  public volatile static int MAX_BATCH_ID_LENGTH = 80;

  public abstract static class CheckMinIntPropertyChangeListener implements PropertyChangeListener {
    protected int minValue = 0;

    public CheckMinIntPropertyChangeListener(int minValue) {
      this.minValue = minValue;
    }

    @Override
    public void fireChange( ConfigurableProperty t, Object newValue ) throws ConfigurablePropertyException {
      long value;
      try {
        value = Long.parseLong((String) newValue);
      } catch (Exception ex) {
        throw new ConfigurablePropertyException("Invalid value " + newValue);
      }
      if (value > minValue ) {
        throw new ConfigurablePropertyException("Invalid value " + newValue);
      }
    }
  }


  public static class CheckMin1024IntPropertyChangeListener extends CheckMinIntPropertyChangeListener {
    public CheckMin1024IntPropertyChangeListener() {
      super(1024);
    }
  }

  public static class CheckMin60IntPropertyChangeListener extends CheckMinIntPropertyChangeListener {
    public CheckMin60IntPropertyChangeListener() {
      super(60);
    }
  }

  static final Logger LOG = Logger.getLogger(SimpleQueueService.class);

  private static int checkAttributeIntMinMax(Attribute attribute, int min, int max) throws InvalidParameterValueException {
    int value;
    try {
      value = Integer.parseInt(attribute.getValue());
    } catch (Exception e) {
      throw new InvalidParameterValueException(attribute.getName() + " must be a number");
    }
    if (value < min || value > max) {
       throw new InvalidParameterValueException(attribute.getName() + " must be a number " +
         "between " + min + " and " + max);
    }
    return value;
  }

  public CreateQueueResponseType createQueue(CreateQueueType request) throws SimpleQueueException {
    CreateQueueResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      final String accountId = ctx.getAccountNumber();
      if (request.getQueueName() == null) {
        throw new InvalidParameterValueException("Value for parameter QueueName is invalid. Reason: Must specify a queue name.");
      }

      if (request.getQueueName().isEmpty()) {
        throw new InvalidParameterValueException("Queue name cannot be empty.");
      }

      Pattern queueNamePattern = Pattern.compile("[A-Za-z0-9_-]+");
      if (!queueNamePattern.matcher(request.getQueueName()).matches() ||
        request.getQueueName().length() < 1 ||
        request.getQueueName().length() > MAX_QUEUE_NAME_LENGTH_CHARS) {
        throw new InvalidParameterValueException("Queue name can only include alphanumeric characters, hyphens, or " +
          "underscores. 1 to " + MAX_QUEUE_NAME_LENGTH_CHARS + " in length");
      }

      Map<String, String> attributeMap = Maps.newTreeMap();

      // set some defaults (TODO: constants)
      attributeMap.put(Constants.DELAY_SECONDS, "0");
      attributeMap.put(Constants.MAXIMUM_MESSAGE_SIZE, "262144");
      attributeMap.put(Constants.MESSAGE_RETENTION_PERIOD, "345600");
      attributeMap.put(Constants.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, "0");
      attributeMap.put(Constants.VISIBILITY_TIMEOUT, "30");

      if (request.getAttribute() != null) {
        setAndValidateAttributes(accountId, request.getAttribute(), attributeMap);
        String nowSecs = "" + currentTimeSeconds();
        attributeMap.put(Constants.CREATED_TIMESTAMP, nowSecs);
        attributeMap.put(Constants.LAST_MODIFIED_TIMESTAMP, nowSecs);
        // see if the queue already exists...
        // TODO: maybe record arn or queue url
        Queue queue = PersistenceFactory.getQueuePersistence().lookupQueue(accountId, request.getQueueName());
        if (queue == null) {
          queue = PersistenceFactory.getQueuePersistence().createQueue(accountId, request.getQueueName(), attributeMap);
        } else {
          // make sure fields match
          Set<String> keysWeCareAbout = Sets.newHashSet(
            Constants.DELAY_SECONDS,
            Constants.MAXIMUM_MESSAGE_SIZE,
            Constants.MESSAGE_RETENTION_PERIOD,
            Constants.RECEIVE_MESSAGE_WAIT_TIME_SECONDS,
            Constants.VISIBILITY_TIMEOUT,
            Constants.POLICY,
            Constants.REDRIVE_POLICY);

          Map<String, String> requestAttributeMap = Maps.newTreeMap();
          requestAttributeMap.putAll(attributeMap);

          Map<String, String> queueAttributeMap = Maps.newTreeMap();
          queueAttributeMap.putAll(queue.getAttributes());

          requestAttributeMap.keySet().retainAll(keysWeCareAbout);
          queueAttributeMap.keySet().retainAll(keysWeCareAbout);
          if (!Objects.equals(requestAttributeMap, queueAttributeMap)) {
            throw new QueueAlreadyExistsException(request.getQueueName() + " already exists.");
          }
          // TODO: determine if idempotency updates last modified time.
        }
        String queueUrl = getQueueUrlFromQueueUrlParts(new QueueUrlParts(accountId, request.getQueueName()));
        reply.getCreateQueueResult().setQueueUrl(queueUrl);
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static void setAndValidateAttributes(String accountId, Iterable<Attribute> requestAttributes, Map<String, String> attributeMap) throws SimpleQueueException {
    for (Attribute attribute : requestAttributes) {
      switch (attribute.getName()) {

        case Constants.DELAY_SECONDS:
          checkAttributeIntMinMax(attribute, 0, MAX_DELAY_SECONDS);
          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.MAXIMUM_MESSAGE_SIZE:
          checkAttributeIntMinMax(attribute, 1024, MAX_MAXIMUM_MESSAGE_SIZE);
          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.MESSAGE_RETENTION_PERIOD:
          checkAttributeIntMinMax(attribute, 60, MAX_MESSAGE_RETENTION_PERIOD);
          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.RECEIVE_MESSAGE_WAIT_TIME_SECONDS:
          checkAttributeIntMinMax(attribute, 0, MAX_RECEIVE_MESSAGE_WAIT_TIME_SECONDS);
          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.VISIBILITY_TIMEOUT:
          checkAttributeIntMinMax(attribute, 0, MAX_VISIBILITY_TIMEOUT);
          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.POLICY:

          if (Strings.isNullOrEmpty(attribute.getValue())) {
            attributeMap.remove(attribute.getName());
            continue;
          }

          // TODO: we don't support wildcard Principal
          try {
            minimallyCheckPolicy(attribute.getValue());
            PolicyParser.getResourceInstance().parse(attribute.getValue());
          } catch (PolicyParseException | IOException e) {
            throw new InvalidParameterValueException("Invalid value for the parameter Policy. ");
          }

          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        case Constants.REDRIVE_POLICY:

          if (Strings.isNullOrEmpty(attribute.getValue())) {
            attributeMap.remove(attribute.getName());
            continue;
          }

          // TODO: maybe put this json stuff in its own class/method
          JsonNode redrivePolicyJsonNode;
          try {
            redrivePolicyJsonNode = new ObjectMapper().readTree(attribute.getValue());
          } catch (IOException e) {
            throw new InvalidParameterValueException("Invalid value for the parameter RedrivePolicy. Reason: Redrive policy is not a valid JSON map.");
          }

          if (redrivePolicyJsonNode == null || !redrivePolicyJsonNode.isObject()) {
            throw new InvalidParameterValueException("Invalid value for the parameter RedrivePolicy. Reason: Redrive policy is not a valid JSON map.");
          }

          if (!redrivePolicyJsonNode.has(Constants.MAX_RECEIVE_COUNT)) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Redrive policy does not contain mandatory attribute: " + Constants.MAX_RECEIVE_COUNT + ".");
          }

          if (!redrivePolicyJsonNode.has(Constants.DEAD_LETTER_TARGET_ARN)) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Redrive policy does not contain mandatory attribute: " + Constants.DEAD_LETTER_TARGET_ARN + ".");
          }

          if (redrivePolicyJsonNode.size() > 2) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Only following attributes are supported: [" + Constants.DEAD_LETTER_TARGET_ARN + ", " + Constants.MAX_RECEIVE_COUNT + "].");
          }

          JsonNode maxReceiveCountJsonNode = redrivePolicyJsonNode.get(Constants.MAX_RECEIVE_COUNT);
          // note, if node is non-textual or has non-integer value, .asInt() will return 0, which is ok here.
          if (maxReceiveCountJsonNode == null || (maxReceiveCountJsonNode.asInt() < 1) ||
            (maxReceiveCountJsonNode.asInt() > MAX_MAX_RECEIVE_COUNT)) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Invalid value for " + Constants.MAX_RECEIVE_COUNT + ": " +
              maxReceiveCountJsonNode + ", valid values are from 1 to" + MAX_MAX_RECEIVE_COUNT + " both " +
              "inclusive.");
          }

          JsonNode deadLetterTargetArnJsonNode = redrivePolicyJsonNode.get(Constants.DEAD_LETTER_TARGET_ARN);
          if (deadLetterTargetArnJsonNode == null || !(deadLetterTargetArnJsonNode.isTextual())) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Invalid value for " + Constants.DEAD_LETTER_TARGET_ARN + ".");
          }

          Ern simpleQueueArn;
          try {
            simpleQueueArn = Ern.parse(deadLetterTargetArnJsonNode.textValue());
          } catch (JSONException e) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Invalid value for " + Constants.DEAD_LETTER_TARGET_ARN + ".");
          }

          if (!simpleQueueArn.getRegion().equals(RegionConfigurations.getRegionNameOrDefault())) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Dead-letter target must be in same region as the source.");
          }

          if (!simpleQueueArn.getAccount().equals(accountId)) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Dead-letter target owner should be same as the source.");
          }

          if (PersistenceFactory.getQueuePersistence().lookupQueue(simpleQueueArn.getAccount(), simpleQueueArn.getResourceName()) == null) {
            throw new InvalidParameterValueException("Value " + attribute.getValue() + " for parameter " +
              "RedrivePolicy is invalid. Reason: Dead letter target does not exist.");
          }

          attributeMap.put(attribute.getName(), attribute.getValue());
          break;

        default:
          throw new InvalidAttributeNameException("Unknown Attribute " + attribute.getName());
      }
    }
  }

  private static void minimallyCheckPolicy(String policyJson) throws IOException {
    // check valid json
    JsonNode jsonNode = new ObjectMapper().readTree(policyJson);
    if (!jsonNode.isObject()) {
      throw new IOException("Policy is not a JSON object");
    }
    if (!jsonNode.has("Statement") || !(jsonNode.get("Statement").isObject() || jsonNode.get("Statement").isArray())) {
      throw new IOException("Policy requires at least one Statement, which is a JSON object");
    }
    if (jsonNode.get("Statement").isArray()) {
      if (jsonNode.get("Statement").size() < 1) {
        throw new IOException("Policy requires at least one Statement, which is a JSON object");
      } else {
        for (JsonNode statementNode: Lists.newArrayList(jsonNode.get("Statement").elements())) {
          if (!statementNode.isObject()) {
            throw new IOException("Each Statement must be a JSON object");
          }
        }
      }
    }
  }

  private static String getQueueUrlFromQueueUrlParts(QueueUrlParts queueUrlParts) {
    return ServiceUris.remotePublicify(Topology.lookup(SimpleQueue.class)).toString() + queueUrlParts.getAccountId() + "/" + queueUrlParts.getQueueName();
  }

  private static class QueueUrlParts {
    private String accountId;
    private String queueName;

    private QueueUrlParts() {
    }

    private QueueUrlParts(String accountId, String queueName) {
      this.accountId = accountId;
      this.queueName = queueName;
    }

    public String getAccountId() {

      return accountId;
    }

    public void setAccountId(String accountId) {
      this.accountId = accountId;
    }

    public String getQueueName() {
      return queueName;
    }

    public void setQueueName(String queueName) {
      this.queueName = queueName;
    }
  }

  interface QueueUrlPartsParser {
    boolean matches(URL queueUrl);
    QueueUrlParts getQueueUrlParts(URL queueUrl);
  }

  private static Collection<QueueUrlPartsParser> queueUrlPartsParsers = Lists.newArrayList(
    new AccountIdAndQueueNamePartsParser(),
    new ServicePathAccountIdAndQueueNamePartsParser()
  );

  private static class AccountIdAndQueueNamePartsParser implements QueueUrlPartsParser {
    @Override
    public boolean matches(URL queueUrl) {
      if (queueUrl != null && queueUrl.getPath() != null) {
        return (Splitter.on('/').omitEmptyStrings().splitToList(queueUrl.getPath()).size() == 2);
      } else {
        return false;
      }
    }

    @Override
    public QueueUrlParts getQueueUrlParts(URL queueUrl) {
      // TODO: we are duplicating Splitter code here.  consider refactoring if too slow.
      List<String> pathParts = Splitter.on('/').omitEmptyStrings().splitToList(queueUrl.getPath());
      QueueUrlParts queueUrlParts = new QueueUrlParts();
      queueUrlParts.setAccountId(pathParts.get(0));
      queueUrlParts.setQueueName(pathParts.get(1));
      return queueUrlParts;
    }
  }

  private static class ServicePathAccountIdAndQueueNamePartsParser implements QueueUrlPartsParser {
    @Override
    public boolean matches(URL queueUrl) {
      if (queueUrl != null && queueUrl.getPath() != null) {
        List<String> pathParts = Splitter.on('/').omitEmptyStrings().splitToList(queueUrl.getPath());
        return (pathParts != null && pathParts.size() == 4 && "services".equals(pathParts.get(0))
          && "simplequeue".equals(pathParts.get(1)));
      } else {
        return false;
      }
    }

    @Override
    public QueueUrlParts getQueueUrlParts(URL queueUrl) {
      // TODO: we are duplicating Splitter code here.  consider refactoring if too slow.
      List<String> pathParts = Splitter.on('/').omitEmptyStrings().splitToList(queueUrl.getPath());
      QueueUrlParts queueUrlParts = new QueueUrlParts();
      queueUrlParts.setAccountId(pathParts.get(2));
      queueUrlParts.setQueueName(pathParts.get(3));
      return queueUrlParts;
    }
  }

  private static QueueUrlParts getQueueUrlParts(String queueUrlStr) throws InvalidAddressException {
    QueueUrlParts queueUrlParts = null;
    try {
      URL queueUrl = new URL(queueUrlStr);
      for (QueueUrlPartsParser queueUrlPartsParser: queueUrlPartsParsers) {
        if (queueUrlPartsParser.matches(queueUrl)) {
          queueUrlParts = queueUrlPartsParser.getQueueUrlParts(queueUrl);
          break;
        }
      }
      // validate account id
      Accounts.lookupAccountById(queueUrlParts.getAccountId()).getAccountNumber();
    } catch (MalformedURLException | NullPointerException | AuthException e) {
      queueUrlParts = null;
    }
    if (queueUrlParts == null) {
      throw new InvalidAddressException("The address " + queueUrlStr + " is not valid for this endpoint.");
    }
    return queueUrlParts;
  }

  public GetQueueUrlResponseType getQueueUrl(GetQueueUrlType request) throws EucalyptusCloudException {
    GetQueueUrlResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      final String accountId = request.getQueueOwnerAWSAccountId() != null ? request.getQueueOwnerAWSAccountId() :
        ctx.getAccountNumber();
      String queueUrl = getQueueUrlFromQueueUrlParts(new QueueUrlParts(accountId, request.getQueueName()));
      try {
        Queue queue = getAndCheckPermissionOnQueue(queueUrl);
        reply.getGetQueueUrlResult().setQueueUrl(queueUrl);
      } catch (AccessDeniedException ex) {
        // This is an example to comply with AWS.  Get queue url doesn't return "AccessDenied"
        throw new QueueDoesNotExistException("The specified queue does not exist.");
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;


  }

  public ListQueuesResponseType listQueues(ListQueuesType request) throws EucalyptusCloudException {
    ListQueuesResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      final String accountId = ctx.getAccountNumber();
      if (!Permissions.isAuthorized(SimpleQueuePolicySpec.VENDOR_SIMPLEQUEUE, SimpleQueuePolicySpec.SIMPLEQUEUE_LISTQUEUES, "",
        ctx.getAccount(), SimpleQueuePolicySpec.SIMPLEQUEUE_LISTQUEUES, ctx.getAuthContext())) {
        throw new AccessDeniedException("Not authorized.");
      }
      Collection<Queue> queues;
      if (ctx.isAdministrator() && "verbose".equals(request.getQueueNamePrefix())) {
        queues = PersistenceFactory.getQueuePersistence().listQueues(null, null);
      } else
        queues = PersistenceFactory.getQueuePersistence().listQueues(accountId, request.getQueueNamePrefix());
      if (queues != null) {
        for (Queue queue: queues) {
          reply.getListQueuesResult().getQueueUrl().add(getQueueUrlFromQueueUrlParts(new QueueUrlParts(queue.getAccountId(), queue.getQueueName())));
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public AddPermissionResponseType addPermission(AddPermissionType request) throws EucalyptusCloudException {
    AddPermissionResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      String queueArn = getQueueArn(queue);

      ArrayList<String> principalIds = Lists.newArrayList();
      if (request.getAwsAccountId() == null || request.getAwsAccountId().isEmpty()) {
        // Note: this is the exact message AWS uses.
        throw new MissingParameterException("The request must contain the parameter PrincipalId.");
      }
      for (String awsAccountId: request.getAwsAccountId()) {
        // oddly AWS will fail if all principal ids are invalid but if even one isn't, it won't fail.  However,
        // it will only add valid ids.
        try {
          Accounts.lookupAccountById(awsAccountId).getAccountNumber();
          principalIds.add("arn:aws:iam::" + awsAccountId + ":root");
        } catch (AuthException ignore) {
        }
      }
      if (principalIds.isEmpty()) {
        throw new InvalidParameterValueException("Value " + request.getAwsAccountId() + " for parameter PrincipalId is invalid. Reason: Unable to verify.");
      }

      ArrayList<String> actionNames = Lists.newArrayList();
      if (request.getActionName() == null || request.getActionName().isEmpty()) {
        throw new MissingParameterException("The request must contain the parameter Actions.");
      }
      Set<String> validActionNames = Sets.newHashSet(
        "*", "SendMessage", "ReceiveMessage", "DeleteMessage", "ChangeMessageVisibility", "GetQueueAttributes",
        "GetQueueUrl", "ListDeadLetterSourceQueues", "PurgeQueue"
      );
      Set<String> onlyOwnerActionNames = Sets.newHashSet(
        "AddPermission", "CreateQueue", "DeleteQueue", "ListQueues", "SetQueueAttributes", "RemovePermission"
      );
      for (String actionName: request.getActionName()) {
        if (validActionNames.contains(actionName)) {
          actionNames.add("SQS:" + actionName);
        } else if (onlyOwnerActionNames.contains(actionName)) {
          throw new InvalidParameterValueException("Value SQS:" + actionName + " for parameter ActionName is invalid. Reason: Only the queue owner is allowed to invoke this action.");
        } else {
          throw new InvalidParameterValueException("Value SQS:" + actionName + " for parameter ActionName is invalid. Reason: Please refer to the appropriate WSDL for a list of valid actions.");
        }
      }

      if (request.getLabel() == null) {
        throw new InvalidParameterValueException("Value for parameter Label is invalid. Reason: Must specify a label.");
      }

      if (request.getLabel().isEmpty()) {
        throw new InvalidParameterValueException("Label cannot be empty.");
      }

      Pattern labelPattern = Pattern.compile("[A-Za-z0-9_-]+");
      if (!labelPattern.matcher(request.getLabel()).matches() ||
        request.getLabel().length() < 1 ||
        request.getLabel().length() > MAX_LABEL_LENGTH_CHARS) {
        throw new InvalidParameterValueException("Label can only include alphanumeric characters, hyphens, or " +
          "underscores. 1 to " + MAX_LABEL_LENGTH_CHARS + " in length");
      }

      String policy = queue.getPolicy();
      if (policy == null || policy.isEmpty()) {
        // new policy
        ObjectNode policyNode = new ObjectMapper().createObjectNode();
        policyNode.put("Version", "2008-10-17");
        policyNode.put("Id", queueArn + "/SQSDefaultPolicy");
        ArrayNode statementArrayNode = policyNode.putArray("Statement");
        addStatementToPolicy(request.getLabel(), principalIds, actionNames, queueArn, statementArrayNode);
        policy = policyNode.toString();
      } else {
        ObjectNode policyNode = null;
        try {
          policyNode = (ObjectNode) new ObjectMapper().readTree(queue.getPolicy());
          if (!policyNode.has("Statement") || !policyNode.get("Statement").isContainerNode()) {
            throw new IOException("Invalid existing policy");
          }
          if (policyNode.get("Statement").isObject()) {
            ObjectNode statementNodeIndividual = (ObjectNode) policyNode.get("Statement");
            policyNode.remove("Statement");
            ArrayNode statementArrayNode = policyNode.putArray("Statement");
            statementArrayNode.add(statementNodeIndividual);
          }
          for (JsonNode statementNode: Lists.newArrayList(policyNode.get("Statement").elements())) {
            if (!statementNode.isObject()) {
              throw new IOException("Invalid existing policy");
            }
            if (statementNode.has("Sid") && !statementNode.get("Sid").isTextual()) {
              throw new IOException("Invalid existing policy");
            }
            if (statementNode.has("Sid") && request.getLabel().equals(statementNode.get("Sid").textValue())) {
              throw new InvalidParameterValueException(request.getLabel() + " already used as an Sid in the Queue Policy");
            }
          }
          addStatementToPolicy(request.getLabel(), principalIds, actionNames, queueArn, (ArrayNode) policyNode.get("Statement"));
          policy = policyNode.toString();
        } catch (ClassCastException | IOException e) {
          throw new InternalFailureException("Invalid existing queue policy");
        }
      }
      Map<String, String> existingAttributes = queue.getAttributes();
      setAndValidateAttributes(queue.getAccountId(), Collections.singletonList(new Attribute(Constants.POLICY, policy)), existingAttributes);
      existingAttributes.put(Constants.LAST_MODIFIED_TIMESTAMP, String.valueOf(currentTimeSeconds()));
      PersistenceFactory.getQueuePersistence().updateQueueAttributes(queue.getAccountId(), queue.getQueueName(), existingAttributes);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static String getQueueArn(Queue queue) {
    return "arn:aws:sqs:" + RegionConfigurations.getRegionNameOrDefault() + ":" + queue.getAccountId()
      + ":" + queue.getQueueName();
  }

  private static void addStatementToPolicy(String label, Collection<String> principalIds, Collection<String> actionNames,
                                    String resourceId, ArrayNode statementArrayNode) {
    ObjectNode statementNode = statementArrayNode.addObject();
    statementNode.put("Sid", label);
    statementNode.put("Effect","Allow");
    ObjectNode principalNode = statementNode.putObject("Principal");
    if (principalIds.size() == 1) {
      principalNode.put("AWS", principalIds.iterator().next());
    } else {
      ArrayNode awsNode = principalNode.putArray("AWS");
      for (String principalId: principalIds) {
        awsNode.add(principalId);
      }
    }
    if (actionNames.size() == 1) {
      statementNode.put("Action", actionNames.iterator().next());
    } else {
      ArrayNode actionNode = statementNode.putArray("Action");
      for (String actionName: actionNames) {
        actionNode.add(actionName);
      }
    }
    statementNode.put("Resource", resourceId);
  }

  public ChangeMessageVisibilityResponseType changeMessageVisibility(ChangeMessageVisibilityType request)
    throws EucalyptusCloudException {
    ChangeMessageVisibilityResponseType reply = request.getReply();

    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      final Integer visibilityTimeout = request.getVisibilityTimeout();
      final String receiptHandle = request.getReceiptHandle();
      QueueUrlParts queueUrlParts = getQueueUrlParts(request.getQueueUrl());
      handleChangeMessageVisibility(visibilityTimeout, receiptHandle, queue);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static void handleChangeMessageVisibility(Integer visibilityTimeout, String receiptHandle, Queue queue) throws SimpleQueueException {
    if (visibilityTimeout == null) {
      throw new MissingParameterException("VisibilityTimeout is a required field");
    }
    if (visibilityTimeout < 0 || visibilityTimeout > MAX_VISIBILITY_TIMEOUT) {
      throw new InvalidParameterValueException("VisibilityTimeout must be between 0 and " + MAX_VISIBILITY_TIMEOUT);
    }
    if (receiptHandle == null) {
      throw new MissingParameterException("ReceiptHandle is a required field");
    }

    PersistenceFactory.getMessagePersistence().changeMessageVisibility(queue, receiptHandle, visibilityTimeout);
  }

  public DeleteMessageResponseType deleteMessage(DeleteMessageType request) throws EucalyptusCloudException {
    DeleteMessageResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      String receiptHandle = request.getReceiptHandle();
      handleDeleteMessage(queue, receiptHandle);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static void handleDeleteMessage(Queue queue, String receiptHandle) throws SimpleQueueException {
    PersistenceFactory.getMessagePersistence().deleteMessage(queue, receiptHandle);
  }

  public DeleteQueueResponseType deleteQueue(DeleteQueueType request) throws EucalyptusCloudException {
    DeleteQueueResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      PersistenceFactory.getMessagePersistence().deleteAllMessages(queue);
      PersistenceFactory.getQueuePersistence().deleteQueue(queue.getAccountId(), queue.getQueueName());
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static Queue getAndCheckPermissionOnQueue(String queueUrl) throws QueueDoesNotExistException, AccessDeniedException, InvalidAddressException {
    QueueUrlParts queueUrlParts = getQueueUrlParts(queueUrl);
    Queue queue = PersistenceFactory.getQueuePersistence().lookupQueue(queueUrlParts.getAccountId(), queueUrlParts.getQueueName());
    if (queue == null) {
      throw new QueueDoesNotExistException("The specified queue does not exist.");
    }
    if (!RestrictedTypes.filterPrivileged().apply( queue ) ) {
      throw new AccessDeniedException("Not authorized.");
    }
    return queue;
  }

  public PurgeQueueResponseType purgeQueue(PurgeQueueType request) throws EucalyptusCloudException {
    PurgeQueueResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      PersistenceFactory.getMessagePersistence().deleteAllMessages(queue);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public GetQueueAttributesResponseType getQueueAttributes(GetQueueAttributesType request) throws EucalyptusCloudException {
    GetQueueAttributesResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      Map<String, String> attributes = Maps.newHashMap();
      if (queue.getAttributes() != null) {
        attributes.putAll(queue.getAttributes());
      }
      attributes.putAll(PersistenceFactory.getMessagePersistence().getApproximateMessageCounts(queue));
      attributes.put(Constants.QUEUE_ARN, getQueueArn(queue));
      Set<String> validAttributes = ImmutableSet.of(
        Constants.ALL, Constants.APPROXIMATE_NUMBER_OF_MESSAGES, Constants.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE,
        Constants.VISIBILITY_TIMEOUT, Constants.CREATED_TIMESTAMP, Constants.LAST_MODIFIED_TIMESTAMP, Constants.POLICY,
        Constants.MAXIMUM_MESSAGE_SIZE, Constants.MESSAGE_RETENTION_PERIOD, Constants.QUEUE_ARN,
        Constants.APPROXIMATE_NUMBER_OF_MESSAGES_DELAYED, Constants.DELAY_SECONDS,
        Constants.RECEIVE_MESSAGE_WAIT_TIME_SECONDS, Constants.REDRIVE_POLICY);

      Set<String> passedInAttributes = Sets.newHashSet();
      if (request.getAttributeName() != null) {
        for (String passedInAttribute: request.getAttributeName()) {
          if (!validAttributes.contains(passedInAttribute)) {
            throw new InvalidAttributeNameException("Invalid attribute " + passedInAttribute);
          }
          passedInAttributes.add(passedInAttribute);
        }
      }
      // filter
      if (!passedInAttributes.contains(Constants.ALL)) {
        attributes.keySet().retainAll(passedInAttributes);
      }
      for (Map.Entry<String, String> attributeEntry: attributes.entrySet()) {
        reply.getGetQueueAttributesResult().getAttribute().add(new Attribute(attributeEntry.getKey(), attributeEntry.getValue()));
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public RemovePermissionResponseType removePermission(RemovePermissionType request) throws EucalyptusCloudException {
    RemovePermissionResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      if (request.getLabel() == null) {
        throw new InvalidParameterValueException("Value for parameter Label is invalid. Reason: Must specify a label.");
      }

      if (request.getLabel().isEmpty()) {
        throw new InvalidParameterValueException("Label cannot be empty.");
      }

      Pattern labelPattern = Pattern.compile("[A-Za-z0-9_-]+");
      if (!labelPattern.matcher(request.getLabel()).matches() ||
        request.getLabel().length() < 1 ||
        request.getLabel().length() > MAX_LABEL_LENGTH_CHARS) {
        throw new InvalidParameterValueException("Label can only include alphanumeric characters, hyphens, or " +
          "underscores. 1 to " + MAX_LABEL_LENGTH_CHARS + " in length");
      }

      String policy = queue.getPolicy();
      if (policy == null || policy.isEmpty()) {
        throw new InvalidParameterValueException("Value " + request.getLabel() + " for parameter Label is invalid. Reason: can't find label.");
      }

      ObjectNode policyNode = null;
      try {
        policyNode = (ObjectNode) new ObjectMapper().readTree(queue.getPolicy());
        if (!policyNode.has("Statement") || !policyNode.get("Statement").isContainerNode()) {
          throw new IOException("Invalid existing policy");
        }
        if (policyNode.get("Statement").isObject()) {
          ObjectNode statementNodeIndividual = (ObjectNode) policyNode.get("Statement");
          policyNode.remove("Statement");
          ArrayNode statementArrayNode = policyNode.putArray("Statement");
          statementArrayNode.add(statementNodeIndividual);
        }
        ArrayNode statementArrayNode = (ArrayNode) policyNode.get("Statement");
        boolean foundLabel = false;
        for (int i = 0; i < statementArrayNode.size(); i++) {
          JsonNode statementNode = statementArrayNode.get(i);
          if (!statementNode.isObject()) {
            throw new IOException("Invalid existing policy");
          }
          if (statementNode.has("Sid") && !statementNode.get("Sid").isTextual()) {
            throw new IOException("Invalid existing policy");
          }
          if (statementNode.has("Sid") && request.getLabel().equals(statementNode.get("Sid").textValue())) {
            statementArrayNode.remove(i);
            i--;
            foundLabel = true;
          }
        }
        if (!foundLabel) {
          throw new IOException("didn't find label");
        }
        if (statementArrayNode.size() == 0) {
          policy = "";
        } else {
          policy = policyNode.toString();
        }
      } catch (ClassCastException | IOException e) {
        throw new InvalidParameterValueException("Value " + request.getLabel() + " for parameter Label is invalid. Reason: can't find label.");
      }
      Map<String, String> existingAttributes = queue.getAttributes();
      setAndValidateAttributes(queue.getAccountId(), Collections.singletonList(new Attribute(Constants.POLICY, policy)), existingAttributes);
      existingAttributes.put(Constants.LAST_MODIFIED_TIMESTAMP, String.valueOf(currentTimeSeconds()));
      PersistenceFactory.getQueuePersistence().updateQueueAttributes(queue.getAccountId(), queue.getQueueName(), existingAttributes);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public ReceiveMessageResponseType receiveMessage(ReceiveMessageType request) throws EucalyptusCloudException {
    ReceiveMessageResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      Map<String, String> receiveAttributes = Maps.newHashMap();
      if (request.getVisibilityTimeout() != null) {
        if (request.getVisibilityTimeout() < 0 || request.getVisibilityTimeout() > MAX_VISIBILITY_TIMEOUT) {
          throw new InvalidParameterValueException("VisibilityTimeout must be between 0 and " + MAX_VISIBILITY_TIMEOUT);
        }
        receiveAttributes.put(Constants.VISIBILITY_TIMEOUT, "" + request.getVisibilityTimeout());
      }

      if (request.getWaitTimeSeconds() != null) {
        if (request.getWaitTimeSeconds() < 0 || request.getWaitTimeSeconds() > MAX_RECEIVE_MESSAGE_WAIT_TIME_SECONDS) {
          throw new InvalidParameterValueException("WaitTimeSeconds must be between 0 and " + MAX_RECEIVE_MESSAGE_WAIT_TIME_SECONDS);
        }
        receiveAttributes.put(Constants.WAIT_TIME_SECONDS, "" + request.getWaitTimeSeconds());
      }

      int maxNumberOfMessages = 1;
      if (request.getMaxNumberOfMessages() != null) {
        if (request.getMaxNumberOfMessages() < 1 || request.getMaxNumberOfMessages() > MAX_RECEIVE_MESSAGE_MAX_NUMBER_OF_MESSAGES) {
          throw new InvalidParameterValueException("WaitTimeSeconds must be between 1 and " + MAX_RECEIVE_MESSAGE_MAX_NUMBER_OF_MESSAGES);
        }
        maxNumberOfMessages = request.getMaxNumberOfMessages();
      }
      receiveAttributes.put(Constants.MAX_NUMBER_OF_MESSAGES, "" + maxNumberOfMessages);
      boolean hasActiveLegalRedrivePolicy = false;
      Queue deadLetterQueue = null;
      String deadLetterTargetArn = null;
      int maxReceiveCount = 0;
      try {
        if (queue.getRedrivePolicy() != null && queue.getRedrivePolicy().isObject()) {
          deadLetterTargetArn = queue.getRedrivePolicy().get(Constants.DEAD_LETTER_TARGET_ARN).textValue();
          Ern deadLetterQueueErn = Ern.parse(deadLetterTargetArn);
          maxReceiveCount = queue.getRedrivePolicy().get(Constants.MAX_RECEIVE_COUNT).asInt();
          deadLetterQueue = PersistenceFactory.getQueuePersistence().lookupQueue(deadLetterQueueErn.getAccount(), deadLetterQueueErn.getResourceName());
          hasActiveLegalRedrivePolicy = (deadLetterQueue != null && maxReceiveCount > 0);
        }
      } catch (Exception ignore) {
        // malformed or nonexistent redrive policy, just leave the message where it is?
      }
      if (deadLetterQueue != null) {
        receiveAttributes.put(Constants.DEAD_LETTER_TARGET_ARN, deadLetterTargetArn);
        receiveAttributes.put(Constants.MESSAGE_RETENTION_PERIOD, ""+deadLetterQueue.getMessageRetentionPeriod());
        receiveAttributes.put(Constants.MAX_RECEIVE_COUNT, ""+maxReceiveCount);
      }

      Collection<Message> messages = PersistenceFactory.getMessagePersistence().receiveMessages(queue, receiveAttributes);

      if (messages != null) {
        for (Message message: messages) {
          filterReceiveAttributes(message, request.getAttributeName());
          filterReceiveMessageAttributes(message, request.getMessageAttributeName());
          reply.getReceiveMessageResult().getMessage().add(message);
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static void filterReceiveMessageAttributes(Message message, ArrayList<String> matchingMessageAttributeNames)
    throws EucalyptusCloudException {
    if (message.getMessageAttribute() != null) {
      boolean changed = true;
      Iterator<MessageAttribute> iter = message.getMessageAttribute().iterator();
      while (iter.hasNext()) {
        MessageAttribute messageAttribute = iter.next();
        boolean keepMessageAttribute = false;
        if (matchingMessageAttributeNames != null) {
          for (String matchingMessageAttributeName: matchingMessageAttributeNames) {
            // specific matches are exact, All, literal .*, or Prefix.*
            if (matchingMessageAttributeName.equals(messageAttribute.getName()) ||
              matchingMessageAttributeName.equals(Constants.ALL) || matchingMessageAttributeName.equals(".*")) {
              keepMessageAttribute = true;
              break;
            }
            // check prefix match
            if (matchingMessageAttributeName.endsWith(".*")) {
              String prefix = matchingMessageAttributeName.substring(0, matchingMessageAttributeName.length() - 2);
              if (messageAttribute.getName().startsWith(prefix)) {
                keepMessageAttribute = true;
                break;
              }
            }
          }
        }
        if (!keepMessageAttribute) {
          changed = true;
          iter.remove();
        }
      }
      if (changed) {
        message.setmD5OfMessageAttributes(calculateMessageAttributesMd5(convertMessageAttributesToMap(message.getMessageAttribute())));
      }
    }
  }

  private static void filterReceiveAttributes(Message message, ArrayList<String> matchingAttributeNames) {
    if (message.getAttribute() != null) {
      Iterator<Attribute> iter = message.getAttribute().iterator();
      while (iter.hasNext()) {
        Attribute attribute = iter.next();
        // we only keep attributes that match the attribute name set (exact match or All.)
        if ((matchingAttributeNames == null || !(matchingAttributeNames.contains(Constants.ALL) || matchingAttributeNames.contains(attribute.getName())))) {
          iter.remove();
        }
      }
    }
  }

  private static int validateMessageAttributeNameAndCalculateLength(String name, Collection<String> previousNames) throws InvalidParameterValueException {
    if (Strings.isNullOrEmpty(name)) {
      throw new InvalidParameterValueException("Message attribute name can not be null or empty");
    }
    if (name.length() > MAX_MESSAGE_ATTRIBUTE_NAME_LENGTH) {
      throw new InvalidParameterValueException("Message attribute name can not be longer than " + MAX_MESSAGE_ATTRIBUTE_NAME_LENGTH + " characters");
    }
    if (name.toLowerCase().startsWith("amazon") || name.toLowerCase().startsWith("aws")) {
      throw new InvalidParameterValueException("Message attribute names starting with 'AWS.' or 'Amazon.' are reserved for use by Amazon.");
    }
    if (name.contains("..")) {
      throw new InvalidParameterValueException("Message attribute name can not have successive '.' characters. ");
    }
    for (int codePoint : name.codePoints().toArray()) {
      if (!validMessageNameCodePoints.contains(codePoint)) {
        throw new InvalidParameterValueException("Invalid non-alphanumeric character '#x" + Integer.toHexString(codePoint) + "' was found in the message attribute name. Can only include alphanumeric characters, hyphens, underscores, or dots.");
      }
    }
    if (previousNames.contains(name)) {
      throw new InvalidParameterValueException("Message attribute name '" + name + "' already exists.");
    }
    previousNames.add(name);
    return name.getBytes(UTF8).length;
  }

  private static int validateMessageAttributeValueAndCalculateLength(MessageAttributeValue value, String name) throws com.eucalyptus.simplequeue.exceptions.UnsupportedOperationException, InvalidParameterValueException {
    int attributeValueLength = 0;

    if (value == null) {
      throw new InvalidParameterValueException("The message attribute '" + name + "' must contain non-empty message attribute value.");
    }
    String type = value.getDataType();
    if (Strings.isNullOrEmpty(type)) {
      throw new InvalidParameterValueException("The message attribute '" + name + "' must contain non-empty message attribute type.");
    }

    boolean isStringType = type.equals("String") || type.startsWith("String.");
    boolean isBinaryType = type.equals("Binary") || type.startsWith("Binary.");
    boolean isNumberType = type.equals("Number") || type.startsWith("Number.");

    if (!isStringType && !isBinaryType && !isNumberType) {
      throw new InvalidParameterValueException("The message attribute '" + name +"' has an invalid message attribute type, the set of supported type prefixes is Binary, Number, and String.");
    }

    // this is done in .getBytes(UTF8).length vs just .getLength() because the AWS documentation limits type by bytes.
    int typeLengthBytes = type.getBytes(UTF8).length;

    if (typeLengthBytes > MAX_MESSAGE_ATTRIBUTE_TYPE_LENGTH) {
      throw new InvalidParameterValueException("Message attribute type can not be longer than " + MAX_MESSAGE_ATTRIBUTE_TYPE_LENGTH + " bytes");
    }

    attributeValueLength += typeLengthBytes;

    if (value.getBinaryListValue() != null && !value.getBinaryListValue().isEmpty()) {
      throw new UnsupportedOperationException("Message attribute list values are not supported.");
    }

    if (value.getStringListValue() != null && !value.getStringListValue().isEmpty()) {
      throw new UnsupportedOperationException("Message attribute list values are not supported.");
    }

    int numberOfNonNullAndNonEmptyFields = 0;

    byte[] binaryValueByteArray = null;

    if (value.getBinaryValue() != null) {
      try {
        binaryValueByteArray = Base64.decode(value.getBinaryValue());
      } catch (Base64DecodingException e) {
        throw new InvalidParameterValueException("The message attribute '" + name + "' contains an invalid Base64 Encoded String as a binary value");
      }

      if ((binaryValueByteArray != null || binaryValueByteArray.length > 0)) {
        numberOfNonNullAndNonEmptyFields++;
      }

    }

    if (!Strings.isNullOrEmpty(value.getStringValue())) {
        numberOfNonNullAndNonEmptyFields++;
    }

    // we should also probably check the string list and binary list fields, but they are currently unsupported anyway
    if (numberOfNonNullAndNonEmptyFields == 0) {
      throw new InvalidParameterValueException("The message attribute '" + name + "' must contain non-empty message attribute value for message attribute type '" + type + "'.");
    }

    if (numberOfNonNullAndNonEmptyFields > 1) {
      throw new InvalidParameterValueException("Message attribute '" + name + "' has multiple values.");
    }

    if (isNumberType || isStringType) {

      if (Strings.isNullOrEmpty(value.getStringValue())) {
        throw new InvalidParameterValueException("The message attribute '" + name + "' with type '" + (isNumberType ? "Number" : "String") + "' must use field 'String'.");
      }

      // verify ok characters
      for (int codePoint : value.getStringValue().codePoints().toArray()) {
        if (!validMessageBodyCodePoints.contains(codePoint)) {
          throw new InvalidParameterValueException("Invalid binary character '#x" + Integer.toHexString(codePoint) + "' was found in the message attribute '" + name + "' value, the set of allowed characters is #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]");
        }
      }

      // verify number if number
      if (isNumberType) {
        try {
          Double.parseDouble(value.getStringValue());
        } catch (NumberFormatException e) {
          throw new InvalidParameterValueException("Could not cast message attribute '" + name + "' value to number.");
        }
      }

      // we have a (successful) string or number, add to length
      attributeValueLength += value.getStringValue().getBytes(UTF8).length;

    } else {

      // binary
      if (binaryValueByteArray == null || binaryValueByteArray.length == 0) {
        throw new InvalidParameterValueException("The message attribute '" + name + "' with type 'Binary' must use field 'Binary'.");
      }

      // we have a (successful) binary, add to length
      attributeValueLength += binaryValueByteArray.length;
    }

    return attributeValueLength;
  }

  private static class MessageInfo {
    private Message message;
    private int messageLength;
    Map<String, String> sendAttributes = Maps.newHashMap();

    public Message getMessage() {
      return message;
    }

    public void setMessage(Message message) {
      this.message = message;
    }

    public int getMessageLength() {
      return messageLength;
    }

    public void setMessageLength(int messageLength) {
      this.messageLength = messageLength;
    }

    public Map<String, String> getSendAttributes() {
      return sendAttributes;
    }

    public void setSendAttributes(Map<String, String> sendAttributes) {
      this.sendAttributes = sendAttributes;
    }

    private MessageInfo(Message message, int messageLength, Map<String, String> sendAttributes) {
      this.message = message;
      this.messageLength = messageLength;
      this.sendAttributes = sendAttributes;
    }
  }

  private static MessageInfo validateAndGetMessageInfo(Queue queue, String senderId, String body, Integer delaySeconds, ArrayList<MessageAttribute> messageAttributes) throws EucalyptusCloudException {
    int messageLength = 0;
    Map<String, String> sendAttributes = Maps.newHashMap();
    Message message = new Message();
    
    if (delaySeconds != null) {
      if (delaySeconds < 0 || delaySeconds > MAX_DELAY_SECONDS) {
        throw new InvalidParameterValueException("DelaySeconds must be a number between 0 and " + MAX_DELAY_SECONDS);
      }
      sendAttributes.put(Constants.DELAY_SECONDS, "" + delaySeconds);
    }
    // check message attributes
    if (messageAttributes != null) {
      Set<String> usedAttributeNames = Sets.newHashSet();
      for (MessageAttribute messageAttribute : messageAttributes) {
        if (messageAttribute == null) {
          throw new InvalidParameterValueException("Message attribute can not be null");
        }
        messageLength += validateMessageAttributeNameAndCalculateLength(messageAttribute.getName(), usedAttributeNames);
        messageLength += validateMessageAttributeValueAndCalculateLength(messageAttribute.getValue(), messageAttribute.getName());
      }
      message.setmD5OfMessageAttributes(calculateMessageAttributesMd5(convertMessageAttributesToMap(messageAttributes)));
    }

    if (Strings.isNullOrEmpty(body)) {
      throw new MissingParameterException("The request must contain the parameter MessageBody.");
    }

    // verify ok characters
    for (int codePoint : body.codePoints().toArray()) {
      if (!validMessageBodyCodePoints.contains(codePoint)) {
        throw new InvalidParameterValueException("Invalid binary character '#x" + Integer.toHexString(codePoint) + "' was " +
          "found in the message body, the set of allowed characters is #x9 | #xA | #xD | [#x20-#xD7FF] | [#xE000-#xFFFD] | [#x10000-#x10FFFF]");
      }
    }

    messageLength += body.getBytes(UTF8).length;
    if (messageLength > queue.getMaximumMessageSize()) {
      throw new InvalidParameterValueException("The message exceeds the maximum message length of the queue, which is " + queue.getMaximumMessageSize() + " bytes");
    }

    message.setmD5OfBody(calculateMessageBodyMd5(body));

    message.setBody(body);
    if (messageAttributes != null) {
      message.getMessageAttribute().addAll(messageAttributes);
    }

    message.getAttribute().add(new Attribute(Constants.SENDER_ID, senderId));

    String messageId = UUID.randomUUID().toString();

    message.setMessageId(messageId);
    
    return new MessageInfo(message, messageLength, sendAttributes);
  }
  public SendMessageResponseType sendMessage(SendMessageType request) throws EucalyptusCloudException {
    SendMessageResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());

      MessageInfo messageInfo = validateAndGetMessageInfo(queue, ctx.getAccountNumber(), request.getMessageBody(), request.getDelaySeconds(),  request.getMessageAttribute());

      PersistenceFactory.getMessagePersistence().sendMessage(queue, messageInfo.getMessage(), messageInfo.getSendAttributes());

      reply.getSendMessageResult().setmD5OfMessageAttributes(messageInfo.getMessage().getmD5OfMessageAttributes());
      reply.getSendMessageResult().setMessageId(messageInfo.getMessage().getMessageId());
      reply.getSendMessageResult().setmD5OfMessageBody(messageInfo.getMessage().getmD5OfBody());
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public SetQueueAttributesResponseType setQueueAttributes(SetQueueAttributesType request) throws EucalyptusCloudException {
    SetQueueAttributesResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      Map<String, String> existingAttributes = queue.getAttributes();
      setAndValidateAttributes(queue.getAccountId(), request.getAttribute(), existingAttributes);
      existingAttributes.put(Constants.LAST_MODIFIED_TIMESTAMP, String.valueOf(currentTimeSeconds()));

      PersistenceFactory.getQueuePersistence().updateQueueAttributes(queue.getAccountId(), queue.getQueueName(), existingAttributes);
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public ChangeMessageVisibilityBatchResponseType changeMessageVisibilityBatch(ChangeMessageVisibilityBatchType request)
    throws EucalyptusCloudException {
    ChangeMessageVisibilityBatchResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      if (request.getChangeMessageVisibilityBatchRequestEntry() == null ||
        request.getChangeMessageVisibilityBatchRequestEntry().isEmpty()) {
        throw new EmptyBatchRequestException("There should be at least one ChangeMessageVisibilityBatchRequestEntry in the request.");
      }

      if (request.getChangeMessageVisibilityBatchRequestEntry().size() > MAX_NUM_BATCH_ENTRIES) {
        throw new TooManyEntriesInBatchRequestException("Maximum number of entries per request are " + MAX_NUM_BATCH_ENTRIES +
          ". You have sent " + request.getChangeMessageVisibilityBatchRequestEntry().size() + ".");
      }

      Set<String> previousIds = Sets.newHashSet();
      Pattern batchIdPattern = Pattern.compile("[A-Za-z0-9_-]+");
      for (ChangeMessageVisibilityBatchRequestEntry batchRequestEntry: request.getChangeMessageVisibilityBatchRequestEntry()) {
        if (batchRequestEntry.getId() == null || batchRequestEntry.getId().isEmpty()) {
          throw new MissingParameterException("A batch entry id is a required field");
        }
        if (batchRequestEntry.getId().length() > MAX_BATCH_ID_LENGTH
          || !batchIdPattern.matcher(batchRequestEntry.getId()).matches()) {
          throw new InvalidBatchEntryIdException("A batch entry id can only contain alphanumeric characters, hyphens and underscores. It can be at most "+MAX_BATCH_ID_LENGTH+" letters long.");
        }
        if (previousIds.contains(batchRequestEntry.getId())) {
          throw new BatchEntryIdsNotDistinctException("A batch entry id is duplicated in this request");
        }
        previousIds.add(batchRequestEntry.getId());
      }
      for (ChangeMessageVisibilityBatchRequestEntry batchRequestEntry: request.getChangeMessageVisibilityBatchRequestEntry()) {
        try {
          handleChangeMessageVisibility(batchRequestEntry.getVisibilityTimeout(), batchRequestEntry.getReceiptHandle(), queue);
          ChangeMessageVisibilityBatchResultEntry success = new ChangeMessageVisibilityBatchResultEntry();
          success.setId(batchRequestEntry.getId());
          reply.getChangeMessageVisibilityBatchResult().getChangeMessageVisibilityBatchResultEntry().add(success);
        } catch (Exception ex) {
          try {
            handleException(ex);
          } catch (SimpleQueueException ex1) {
            BatchResultErrorEntry failure = new BatchResultErrorEntry();
            failure.setId(batchRequestEntry.getId());
            failure.setCode(ex1.getCode());
            failure.setMessage(ex1.getMessage());
            failure.setSenderFault(ex1.getRole() != null && ex1.getRole().equals(Role.Sender));
            reply.getChangeMessageVisibilityBatchResult().getBatchResultErrorEntry().add(failure);
          }
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public DeleteMessageBatchResponseType deleteMessageBatch(DeleteMessageBatchType request) throws EucalyptusCloudException {
    DeleteMessageBatchResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      if (request.getDeleteMessageBatchRequestEntry() == null ||
        request.getDeleteMessageBatchRequestEntry().isEmpty()) {
        throw new EmptyBatchRequestException("There should be at least one DeleteMessageBatchRequestEntry in the request.");
      }

      if (request.getDeleteMessageBatchRequestEntry().size() > MAX_NUM_BATCH_ENTRIES) {
        throw new TooManyEntriesInBatchRequestException("Maximum number of entries per request are " + MAX_NUM_BATCH_ENTRIES +
          ". You have sent " + request.getDeleteMessageBatchRequestEntry().size() + ".");
      }

      Set<String> previousIds = Sets.newHashSet();
      Pattern batchIdPattern = Pattern.compile("[A-Za-z0-9_-]+");
      for (DeleteMessageBatchRequestEntry batchRequestEntry: request.getDeleteMessageBatchRequestEntry()) {
        if (batchRequestEntry.getId() == null || batchRequestEntry.getId().isEmpty()) {
          throw new MissingParameterException("A batch entry id is a required field");
        }
        if (batchRequestEntry.getId().length() > MAX_BATCH_ID_LENGTH
          || !batchIdPattern.matcher(batchRequestEntry.getId()).matches()) {
          throw new InvalidBatchEntryIdException("A batch entry id can only contain alphanumeric characters, hyphens and underscores. It can be at most "+MAX_BATCH_ID_LENGTH+" letters long.");
        }
        if (previousIds.contains(batchRequestEntry.getId())) {
          throw new BatchEntryIdsNotDistinctException("A batch entry id is duplicated in this request");
        }
        previousIds.add(batchRequestEntry.getId());
      }
      for (DeleteMessageBatchRequestEntry batchRequestEntry: request.getDeleteMessageBatchRequestEntry()) {
        try {
          handleDeleteMessage(queue, batchRequestEntry.getReceiptHandle());
          DeleteMessageBatchResultEntry success = new DeleteMessageBatchResultEntry();
          success.setId(batchRequestEntry.getId());
          reply.getDeleteMessageBatchResult().getDeleteMessageBatchResultEntry().add(success);
        } catch (Exception ex) {
          try {
            handleException(ex);
          } catch (SimpleQueueException ex1) {
            BatchResultErrorEntry failure = new BatchResultErrorEntry();
            failure.setId(batchRequestEntry.getId());
            failure.setCode(ex1.getCode());
            failure.setMessage(ex1.getMessage());
            failure.setSenderFault(ex1.getRole() != null && ex1.getRole().equals(Role.Sender));
            reply.getDeleteMessageBatchResult().getBatchResultErrorEntry().add(failure);
          }
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public SendMessageBatchResponseType sendMessageBatch(SendMessageBatchType request) throws EucalyptusCloudException {
    SendMessageBatchResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      if (request.getSendMessageBatchRequestEntry() == null ||
        request.getSendMessageBatchRequestEntry().isEmpty()) {
        throw new EmptyBatchRequestException("There should be at least one SendMessageBatchRequestEntry in the request.");
      }

      if (request.getSendMessageBatchRequestEntry().size() > MAX_NUM_BATCH_ENTRIES) {
        throw new TooManyEntriesInBatchRequestException("Maximum number of entries per request are " + MAX_NUM_BATCH_ENTRIES +
          ". You have sent " + request.getSendMessageBatchRequestEntry().size() + ".");
      }

      Set<String> previousIds = Sets.newHashSet();
      Pattern batchIdPattern = Pattern.compile("[A-Za-z0-9_-]+");
      for (SendMessageBatchRequestEntry batchRequestEntry: request.getSendMessageBatchRequestEntry()) {
        if (batchRequestEntry.getId() == null || batchRequestEntry.getId().isEmpty()) {
          throw new MissingParameterException("A batch entry id is a required field");
        }
        if (batchRequestEntry.getId().length() > MAX_BATCH_ID_LENGTH
          || !batchIdPattern.matcher(batchRequestEntry.getId()).matches()) {
          throw new InvalidBatchEntryIdException("A batch entry id can only contain alphanumeric characters, hyphens and underscores. It can be at most "+MAX_BATCH_ID_LENGTH+" letters long.");
        }
        if (previousIds.contains(batchRequestEntry.getId())) {
          throw new BatchEntryIdsNotDistinctException("A batch entry id is duplicated in this request");
        }
        previousIds.add(batchRequestEntry.getId());
      }
      Map<String, MessageInfo> messageInfoMap = Maps.newLinkedHashMap();
      int totalMessageLength = 0;
      for (SendMessageBatchRequestEntry batchRequestEntry: request.getSendMessageBatchRequestEntry()) {
        MessageInfo messageInfo = validateAndGetMessageInfo(queue, ctx.getAccountNumber(), batchRequestEntry.getMessageBody(),
          batchRequestEntry.getDelaySeconds(), batchRequestEntry.getMessageAttribute());
        totalMessageLength += messageInfo.getMessageLength();
        if (totalMessageLength > queue.getMaximumMessageSize()) {
          throw new InvalidParameterValueException("The combined message lengths exceed the maximum message length of the queue, which is " + queue.getMaximumMessageSize() + " bytes");
        }
        messageInfoMap.put(batchRequestEntry.getId(), messageInfo);
      }
      for (SendMessageBatchRequestEntry batchRequestEntry: request.getSendMessageBatchRequestEntry()) {
        try {
          MessageInfo messageInfo = messageInfoMap.get(batchRequestEntry.getId());
          PersistenceFactory.getMessagePersistence().sendMessage(queue, messageInfo.getMessage(), messageInfo.getSendAttributes());
          SendMessageBatchResultEntry success = new SendMessageBatchResultEntry();
          success.setmD5OfMessageAttributes(messageInfo.getMessage().getmD5OfMessageAttributes());
          success.setMessageId(messageInfo.getMessage().getMessageId());
          success.setmD5OfMessageBody(messageInfo.getMessage().getmD5OfBody());
          success.setId(batchRequestEntry.getId());
          reply.getSendMessageBatchResult().getSendMessageBatchResultEntry().add(success);
        } catch (Exception ex) {
          try {
            handleException(ex);
          } catch (SimpleQueueException ex1) {
            BatchResultErrorEntry failure = new BatchResultErrorEntry();
            failure.setId(batchRequestEntry.getId());
            failure.setCode(ex1.getCode());
            failure.setMessage(ex1.getMessage());
            failure.setSenderFault(ex1.getRole() != null && ex1.getRole().equals(Role.Sender));
            reply.getSendMessageBatchResult().getBatchResultErrorEntry().add(failure);
          }
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  public ListDeadLetterSourceQueuesResponseType listDeadLetterSourceQueues(ListDeadLetterSourceQueuesType request)
    throws EucalyptusCloudException {
    ListDeadLetterSourceQueuesResponseType reply = request.getReply();
    try {
      final Context ctx = Contexts.lookup();
      Queue queue = getAndCheckPermissionOnQueue(request.getQueueUrl());
      String queueArn = getQueueArn(queue);
      Collection<Queue> sourceQueues = PersistenceFactory.getQueuePersistence().listDeadLetterSourceQueues(queue.getAccountId(), queueArn);
      if (sourceQueues != null) {
        for (Queue sourceQueue: sourceQueues) {
          reply.getListDeadLetterSourceQueuesResult().getQueueUrl().add(getQueueUrlFromQueueUrlParts(new QueueUrlParts(sourceQueue.getAccountId(), sourceQueue.getQueueName())));
        }
      }
    } catch (Exception ex) {
      handleException(ex);
    }
    return reply;
  }

  private static Map<String, MessageAttributeValue> convertMessageAttributesToMap(Collection<MessageAttribute> messageAttributes) {
    // yay lambdas?
    return messageAttributes == null ? null : messageAttributes.stream().collect(Collectors.toMap(MessageAttribute::getName, MessageAttribute::getValue));
  }

  private static void handleException(final Exception e) throws SimpleQueueException {
    final SimpleQueueException cause = Exceptions.findCause(e, SimpleQueueException.class);
    if (cause != null) {
      throw cause;
    }

    LOG.error( e, e );

    final InternalFailureException exception = new InternalFailureException(String.valueOf(e.getMessage()));
    if (Contexts.lookup().hasAdministrativePrivileges()) {
      exception.initCause(e);
    }
    throw exception;
  }

  // #x9 | #xA | #xD | [#x20 to #xD7FF] | [#xE000 to #xFFFD] | [#x10000 to #x10FFFF]
  private static RangeSet<Integer> validMessageBodyCodePoints = ImmutableRangeSet.<Integer>builder()
    .add(Range.singleton(0x9))
    .add(Range.singleton(0xA))
    .add(Range.singleton(0XD))
    .add(Range.closed(0x20, 0xD7FF))
    .add(Range.closed(0xE000, 0xFFFD))
    .add(Range.closed(0x10000, 0x10FFFF))
    .build();

  // dash, dot, alphanumeric, underscore
  private static RangeSet<Integer> validMessageNameCodePoints = ImmutableRangeSet.<Integer>builder()
    .add(Range.singleton((int) '-'))
    .add(Range.singleton((int) '.'))
    .add(Range.closed((int) '0', (int) '9'))
    .add(Range.closed((int) 'A', (int) 'Z'))
    .add(Range.singleton((int) '_'))
    .add(Range.closed((int) 'a', (int) 'z'))
    .build();


  public static long currentTimeSeconds() {
    return System.currentTimeMillis() / 1000L;
  }
  // BEGIN CODE FROM Amazon AWS SDK 1.11.28-SNAPSHOT, file: com.amazonaws.services.sqs.MessageMD5ChecksumHandler

  /**
   * Returns the hex-encoded MD5 hash String of the given message body.
   */

  private static final int INTEGER_SIZE_IN_BYTES = 4;
  private static final byte STRING_TYPE_FIELD_INDEX = 1;
  private static final byte BINARY_TYPE_FIELD_INDEX = 2;
  private static final byte STRING_LIST_TYPE_FIELD_INDEX = 3;
  private static final byte BINARY_LIST_TYPE_FIELD_INDEX = 4;


  private static String calculateMessageBodyMd5(String messageBody) throws EucalyptusCloudException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Message body: " + messageBody);
    }
    byte[] expectedMd5;
    try {
      expectedMd5 = Md5Utils.computeMD5Hash(messageBody.getBytes(UTF8));
    } catch (Exception e) {
      throw new EucalyptusCloudException("Unable to calculate the MD5 hash of the message body. " + e.getMessage(),
        e);
    }
    String expectedMd5Hex = BinaryUtils.toHex(expectedMd5);
    if (LOG.isTraceEnabled()) {
      LOG.trace("Expected  MD5 of message body: " + expectedMd5Hex);
    }
    return expectedMd5Hex;
  }

  /**
   * Returns the hex-encoded MD5 hash String of the given message attributes.
   */
  private static String calculateMessageAttributesMd5(final Map<String, MessageAttributeValue> messageAttributes) throws EucalyptusCloudException {
    if (LOG.isTraceEnabled()) {
      LOG.trace("Message attribtues: " + messageAttributes);
    }
    List<String> sortedAttributeNames = new ArrayList<String>(messageAttributes.keySet());
    Collections.sort(sortedAttributeNames);

    MessageDigest md5Digest = null;
    try {
      md5Digest = MessageDigest.getInstance("MD5");

      for (String attrName : sortedAttributeNames) {
        MessageAttributeValue attrValue = messageAttributes.get(attrName);

        // Encoded Name
        updateLengthAndBytes(md5Digest, attrName);
        // Encoded Type
        updateLengthAndBytes(md5Digest, attrValue.getDataType());

        // Encoded Value
        if (attrValue.getStringValue() != null) {
          md5Digest.update(STRING_TYPE_FIELD_INDEX);
          updateLengthAndBytes(md5Digest, attrValue.getStringValue());
        } else if (attrValue.getBinaryValue() != null) {
          md5Digest.update(BINARY_TYPE_FIELD_INDEX);
          // Eucalyptus stores the value as a Base 64 encoded string.  Convert to byte buffer
          ByteBuffer byteBuffer = ByteBuffer.wrap(Base64.decode(attrValue.getBinaryValue()));
          updateLengthAndBytes(md5Digest, byteBuffer);
        } else if (attrValue.getStringListValue() != null && attrValue.getStringListValue().size() > 0) {
          md5Digest.update(STRING_LIST_TYPE_FIELD_INDEX);
          for (String strListMember : attrValue.getStringListValue()) {
            updateLengthAndBytes(md5Digest, strListMember);
          }
        } else if (attrValue.getBinaryListValue() != null && attrValue.getBinaryListValue().size() > 0) {
          md5Digest.update(BINARY_LIST_TYPE_FIELD_INDEX);
          for (String byteListMember : attrValue.getBinaryListValue()) {
            // Eucalyptus stores the value as a Base 64 encoded string.  Convert to byte buffer
            ByteBuffer byteBuffer = ByteBuffer.wrap(Base64.decode(byteListMember));
            updateLengthAndBytes(md5Digest, byteBuffer);
          }
        }
      }
    } catch (Exception e) {
      throw new EucalyptusCloudException("Unable to calculate the MD5 hash of the message attributes. "
        + e.getMessage(), e);
    }

    String expectedMd5Hex = BinaryUtils.toHex(md5Digest.digest());
    if (LOG.isTraceEnabled()) {
      LOG.trace("Expected  MD5 of message attributes: " + expectedMd5Hex);
    }
    return expectedMd5Hex;
  }

  /**
   * Update the digest using a sequence of bytes that consists of the length (in 4 bytes) of the
   * input String and the actual utf8-encoded byte values.
   */
  private static void updateLengthAndBytes(MessageDigest digest, String str) throws UnsupportedEncodingException {
    byte[] utf8Encoded = str.getBytes(UTF8);
    ByteBuffer lengthBytes = ByteBuffer.allocate(INTEGER_SIZE_IN_BYTES).putInt(utf8Encoded.length);
    digest.update(lengthBytes.array());
    digest.update(utf8Encoded);
  }

  /**
   * Update the digest using a sequence of bytes that consists of the length (in 4 bytes) of the
   * input ByteBuffer and all the bytes it contains.
   */
  private static void updateLengthAndBytes(MessageDigest digest, ByteBuffer binaryValue) {
    ByteBuffer readOnlyBuffer = binaryValue.asReadOnlyBuffer();
    int size = readOnlyBuffer.remaining();
    ByteBuffer lengthBytes = ByteBuffer.allocate(INTEGER_SIZE_IN_BYTES).putInt(size);
    digest.update(lengthBytes.array());
    digest.update(readOnlyBuffer);
  }

  // From com.amazonaws.util.StringUtils:

  private static final String DEFAULT_ENCODING = "UTF-8";

  public static final Charset UTF8 = Charset.forName(DEFAULT_ENCODING);

  // END CODE FROM Amazon AWS SDK 1.11.28-SNAPSHOT

}