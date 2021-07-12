// Copyright 2018-2021 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.google.apigee;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class IOUtil {
  private static final int BUF_SIZE = 0x1000;

  public static long copy(InputStream from, OutputStream to) throws IOException {
    if (from == null) throw new IllegalArgumentException("from");
    if (to == null) throw new IllegalArgumentException("to");
    byte[] buf = new byte[BUF_SIZE];
    long total = 0;
    while (true) {
      int r = from.read(buf);
      if (r == -1) {
        break;
      }
      to.write(buf, 0, r);
      total += r;
    }
    return total;
  }

  public static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    byte[] buf = new byte[BUF_SIZE];
    for (int len = is.read(buf); len != -1; len = is.read(buf)) {
      os.write(buf, 0, len);
    }
    return os.toByteArray();
  }
}
