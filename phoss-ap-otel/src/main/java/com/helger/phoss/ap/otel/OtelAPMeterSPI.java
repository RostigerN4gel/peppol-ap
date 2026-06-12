/*
 * Copyright (C) 2026 Philip Helger (www.helger.com)
 * philip[at]helger[dot]com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.helger.phoss.ap.otel;

import com.helger.annotation.style.IsSPIImplementation;
import com.helger.phoss.ap.api.CPhossAPVersion;
import com.helger.phoss.ap.api.otel.CPhossAPOtel;
import com.helger.telemetry.otel.OtelTelemetryMeterSPI;

/**
 * Project-specific concrete {@link OtelTelemetryMeterSPI} that wires the phoss AP instrumentation
 * scope name + version into the generic ph-telemetry-otel binding. Registered via
 * {@code META-INF/services/com.helger.telemetry.ITelemetryMeterSPI}.
 *
 * @author Philip Helger
 * @since 0.10.0
 */
@IsSPIImplementation
public final class OtelAPMeterSPI extends OtelTelemetryMeterSPI
{
  public OtelAPMeterSPI ()
  {
    super (CPhossAPOtel.INSTRUMENTATION_SCOPE_NAME, CPhossAPVersion.BUILD_VERSION);
  }
}
