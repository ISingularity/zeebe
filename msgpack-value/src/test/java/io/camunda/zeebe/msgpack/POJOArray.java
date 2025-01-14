/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.msgpack;

import io.camunda.zeebe.msgpack.property.ArrayProperty;
import io.camunda.zeebe.msgpack.value.ValueArray;

public final class POJOArray extends UnpackedObject {

  protected final ArrayProperty<MinimalPOJO> simpleArrayProp;

  public POJOArray() {
    simpleArrayProp = new ArrayProperty<>("simpleArray", MinimalPOJO::new);

    declareProperty(simpleArrayProp);
  }

  public ValueArray<MinimalPOJO> simpleArray() {
    return simpleArrayProp;
  }
}
