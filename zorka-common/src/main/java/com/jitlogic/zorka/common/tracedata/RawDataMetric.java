/*
 * Copyright 2012-2017 Rafal Lewczuk <rafal.lewczuk@jitlogic.com>
 * <p/>
 * This is free software. You can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p/>
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p/>
 * You should have received a copy of the GNU General Public License
 * along with this software. If not, see <http://www.gnu.org/licenses/>.
 */

package com.jitlogic.zorka.common.tracedata;

import java.util.HashMap;
import java.util.Map;

/**
 * Raw data metric. Presents tracked values as is (with optional multiplication).
 */
public class RawDataMetric extends Metric {


    public RawDataMetric(int id, String name, String description, String domain,
                         Map<String, Object> attrs) {
        super(id, name, description, domain, attrs);
    }

    public RawDataMetric(int id, int templateId, String name, String description, String domain,
                         Map<String, Object> attrs) {
        super(id, templateId, name, description, domain, attrs);
    }


    public RawDataMetric(MetricTemplate template, String name, String description, String domain,
                         Map<String, Object> attrs) {
        super(template, name, description, domain, attrs);
    }


    @Override
    public Number getValue(long clock, Object value) {
        return multiply((Number) value);
    }

}
