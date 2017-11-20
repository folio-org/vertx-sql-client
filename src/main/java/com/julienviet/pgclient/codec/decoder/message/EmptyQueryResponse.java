/*
 * Copyright (C) 2017 Julien Viet
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.julienviet.pgclient.codec.decoder.message;

import com.julienviet.pgclient.codec.Message;

/**
 *
 * <p>
 * Identifies the message as a response to an empty query string. (This substitutes for {@link CommandComplete})
 *
 * @author <a href="mailto:emad.albloushi@gmail.com">Emad Alblueshi</a>
 */

public class EmptyQueryResponse implements Message {

  public static final EmptyQueryResponse INSTANCE = new EmptyQueryResponse();

  private EmptyQueryResponse() {}

  @Override
  public String toString() {
    return "EmptyQueryResponse{}";
  }
}