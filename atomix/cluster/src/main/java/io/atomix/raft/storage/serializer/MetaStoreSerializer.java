/*
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.storage.serializer;

import static io.atomix.raft.storage.serializer.SerializerUtil.getRaftMemberType;
import static io.atomix.raft.storage.serializer.SerializerUtil.getSBEType;

import io.atomix.cluster.MemberId;
import io.atomix.raft.cluster.RaftMember;
import io.atomix.raft.cluster.impl.DefaultRaftMember;
import io.atomix.raft.storage.serializer.ConfigurationDecoder.RaftMemberDecoder;
import io.atomix.raft.storage.system.Configuration;
import java.time.Instant;
import java.util.ArrayList;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

public class MetaStoreSerializer {

  private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
  private final ConfigurationEncoder configurationEncoder = new ConfigurationEncoder();
  private final MetaEncoder metaEncoder = new MetaEncoder();

  private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
  private final ConfigurationDecoder configurationDecoder = new ConfigurationDecoder();
  private final MetaDecoder metaDecoder = new MetaDecoder();

  public int writeConfiguration(
      final Configuration configuration, final MutableDirectBuffer buffer, final int offset) {

    headerEncoder
        .wrap(buffer, offset)
        .blockLength(configurationEncoder.sbeBlockLength())
        .templateId(configurationEncoder.sbeTemplateId())
        .schemaId(configurationEncoder.sbeSchemaId())
        .version(configurationEncoder.sbeSchemaVersion());

    configurationEncoder.wrap(buffer, offset + headerEncoder.encodedLength());

    configurationEncoder
        .index(configuration.index())
        .term(configuration.term())
        .timestamp(configuration.time());

    final var raftMemberEncoder =
        configurationEncoder.raftMemberCount(configuration.members().size());
    for (final RaftMember member : configuration.members()) {
      final var memberId = member.memberId().id();
      raftMemberEncoder
          .next()
          .type(getSBEType(member.getType()))
          .updated(member.getLastUpdated().toEpochMilli())
          .memberId(memberId);
    }

    return headerEncoder.encodedLength() + configurationEncoder.encodedLength();
  }

  public Configuration readConfiguration(final DirectBuffer buffer, final int offset) {
    headerDecoder.wrap(buffer, offset);
    if (headerDecoder.version() != configurationEncoder.sbeSchemaVersion()
        || headerDecoder.templateId() != configurationEncoder.sbeTemplateId()) {
      return null;
    }
    configurationDecoder.wrap(
        buffer,
        offset + headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    final long index = configurationDecoder.index();
    final long term = configurationDecoder.term();
    final long timestamp = configurationDecoder.timestamp();

    final RaftMemberDecoder memberDecoder = configurationDecoder.raftMember();
    final ArrayList<RaftMember> members = new ArrayList<>(memberDecoder.count());
    for (final RaftMemberDecoder member : memberDecoder) {
      final RaftMember.Type type = getRaftMemberType(member.type());
      final Instant updated = Instant.ofEpochMilli(member.updated());
      final var memberId = member.memberId();
      members.add(new DefaultRaftMember(MemberId.from(memberId), type, updated));
    }

    return new Configuration(index, term, timestamp, members);
  }

  public void writeTerm(final long term, final MutableDirectBuffer buffer) {
    headerEncoder
        .wrap(buffer, 0)
        .blockLength(metaEncoder.sbeBlockLength())
        .templateId(metaEncoder.sbeTemplateId())
        .schemaId(metaEncoder.sbeSchemaId())
        .version(metaEncoder.sbeSchemaVersion());

    metaEncoder.wrap(buffer, headerEncoder.encodedLength());
    metaEncoder.term(term);
  }

  public long readTerm(final MutableDirectBuffer buffer) {
    headerDecoder.wrap(buffer, 0);
    metaDecoder.wrap(
        buffer,
        headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    return metaDecoder.term();
  }

  public void writeVotedFor(final String memberId, final MutableDirectBuffer buffer) {
    headerEncoder
        .wrap(buffer, 0)
        .blockLength(metaEncoder.sbeBlockLength())
        .templateId(metaEncoder.sbeTemplateId())
        .schemaId(metaEncoder.sbeSchemaId())
        .version(metaEncoder.sbeSchemaVersion());

    metaEncoder.wrap(buffer, headerEncoder.encodedLength());
    metaEncoder.votedFor(memberId);
  }

  public String readVotedFor(final MutableDirectBuffer buffer) {
    headerDecoder.wrap(buffer, 0);
    metaDecoder.wrap(
        buffer,
        headerDecoder.encodedLength(),
        headerDecoder.blockLength(),
        headerDecoder.version());

    return metaDecoder.votedFor();
  }
}