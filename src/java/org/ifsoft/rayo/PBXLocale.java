/**
 * $Revision $
 * $Date $
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

package org.ifsoft.rayo;

import java.util.Locale;

class PBXLocale
{
    private Locale innerLocale = null;
    private String displayName = "";

    public PBXLocale(Locale locale)
    {
        innerLocale = locale;
        setDisplayCountry(locale.getDisplayCountry());
    }

    /**
     * @return the locally stored display name
     */
    public String getDisplayCountry()
    {
        return displayName;
    }

    /**
     * @return the country code from the internally stored Locale
     */
    public String getCountry()
    {
        return innerLocale.getCountry() ;
    }
    /**
     * @return the internally store Locale
     */
    public Locale getLocale()
    {
        return innerLocale;
    }
    /**
     * @param name the String representing the Country Name to be stored locally here
     *
     */
    public void setDisplayCountry(String name)
    {
        displayName = name;
    }
}



