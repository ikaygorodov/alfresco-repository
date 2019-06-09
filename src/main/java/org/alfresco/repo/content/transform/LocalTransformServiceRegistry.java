/*
 * #%L
 * Alfresco Repository
 * %%
 * Copyright (C) 2019 Alfresco Software Limited
 * %%
 * This file is part of the Alfresco software.
 * If the software was purchased under a paid Alfresco license, the terms of
 * the paid license agreement will prevail.  Otherwise, the software is
 * provided under the following open source license terms:
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.alfresco.repo.content.transform;

import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.transform.client.model.config.CombinedConfig;
import org.alfresco.transform.client.model.config.TransformServiceRegistry;
import org.alfresco.transform.client.model.config.TransformServiceRegistryImpl;
import org.alfresco.transform.client.model.config.TransformStep;
import org.alfresco.transform.client.model.config.Transformer;
import org.alfresco.util.PropertyCheck;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Implements {@link TransformServiceRegistry} providing a mechanism of validating if a local transformation
 * (based on {@link LocalTransformer} request is supported. It also extends this interface to provide a
 * {@link #transform} method.
 * @author adavis
 */
public class LocalTransformServiceRegistry extends TransformServiceRegistryImpl implements InitializingBean
{
    private static final Log log = LogFactory.getLog(LocalTransformer.class);

    private static final String LOCAL_TRANSFORMER = "localTransformer.";
    private static final String URL = ".url";
    static final String STRICT_MIMETYPE_CHECK_WHITELIST_MIMETYPES = "transformer.strict.mimetype.check.whitelist.mimetypes";

    private String pipelineConfigFolder;
    private boolean enabled = true;
    private boolean firstTime = true;
    private Properties properties;
    private MimetypeService mimetypeService;
    private TransformerDebug transformerDebug;
    private boolean strictMimeTypeCheck;
    private Map<String, Set<String>> strictMimetypeExceptions;
    private boolean retryTransformOnDifferentMimeType;

    private Map<String, LocalTransformer> transformers = new HashMap<>();

    public void setPipelineConfigFolder(String pipelineConfigFolder)
    {
        this.pipelineConfigFolder = pipelineConfigFolder;
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
        firstTime = true;
    }

    /**
     * The Alfresco global properties.
     */
    public void setProperties(Properties properties)
    {
        this.properties = properties;
    }

    public void setMimetypeService(MimetypeService mimetypeService)
    {
        this.mimetypeService = mimetypeService;
    }

    public void setTransformerDebug(TransformerDebug transformerDebug)
    {
        this.transformerDebug = transformerDebug;
    }

    public void setStrictMimeTypeCheck(boolean strictMimeTypeCheck)
    {
        this.strictMimeTypeCheck = strictMimeTypeCheck;
    }

    public void setRetryTransformOnDifferentMimeType(boolean retryTransformOnDifferentMimeType)
    {
        this.retryTransformOnDifferentMimeType = retryTransformOnDifferentMimeType;
    }

    @Override
    public void afterPropertiesSet() throws Exception
    {
        PropertyCheck.mandatory(this, "mimetypeService", mimetypeService);
        PropertyCheck.mandatory(this, "pipelineConfigFolder", pipelineConfigFolder);
        PropertyCheck.mandatory(this, "properties", properties);
        PropertyCheck.mandatory(this, "transformerDebug", transformerDebug);
        super.afterPropertiesSet();
        transformers.clear();
        strictMimetypeExceptions = getStrictMimetypeExceptions();

        CombinedConfig combinedConfig = new CombinedConfig(getLog());
        List<String> urls = getTEngineUrls();
        combinedConfig.addRemoteConfig(urls, "T-Engine");
        combinedConfig.addLocalConfig(pipelineConfigFolder);
        // TODO read local transformers from an external location
        combinedConfig.register(this);
    }

    @Override
    public void register(Transformer transformer, String baseUrl, String readFrom)
    {
        try
        {
            String name = transformer.getTransformerName();
            if (name == null || transformers.get(name) != null)
            {
                throw new IllegalArgumentException("Local transformer names must exist and be unique (" + name + ").");
            }

            LocalTransformer localTransformer;
            List<TransformStep> pipeline = transformer.getTransformerPipeline();
            if (pipeline == null || pipeline.isEmpty())
            {
                if (baseUrl == null)
                {
                    throw new IllegalArgumentException("Local transformer " + name +
                            " must have its baseUrl set in " + LOCAL_TRANSFORMER+name+URL+
                            " Read from "+readFrom);
                }
                int startupRetryPeriodSeconds = getStartupRetryPeriodSeconds(name);
                localTransformer = new LocalTransformerImpl(name, transformerDebug, mimetypeService,
                         strictMimeTypeCheck, strictMimetypeExceptions, retryTransformOnDifferentMimeType,
                        this, baseUrl, startupRetryPeriodSeconds);
            }
            else
            {
                int transformerCount = pipeline.size();
                if (transformerCount <= 1)
                {
                    throw new IllegalArgumentException("Local pipeline transformer " + name +
                            " must have more than one intermediate transformer defined."+
                            " Read from "+readFrom);
                }

                localTransformer = new LocalPipelineTransformer(name, transformerDebug, mimetypeService,
                        strictMimeTypeCheck, strictMimetypeExceptions, retryTransformOnDifferentMimeType,
                        this);
                for (int i=0; i < transformerCount; i++)
                {
                    TransformStep intermediateTransformerStep = pipeline.get(i);
                    String intermediateTransformerName = intermediateTransformerStep.getTransformerName();
                    if (name == null || transformers.get(name) != null)
                    {
                        throw new IllegalArgumentException("Local pipeline transformer " + name +
                                " did not specified an intermediate transformer name."+
                                " Read from "+readFrom);
                    }

                    LocalTransformer intermediateTransformer = transformers.get(intermediateTransformerName);
                    if (intermediateTransformer == null)
                    {
                        throw new IllegalArgumentException("Local pipeline transformer " + name +
                                " specified an intermediate transformer (" +
                                intermediateTransformerName + " that has not been defined."+
                                " Read from "+readFrom);
                    }

                    String targetMimetype = intermediateTransformerStep.getTargetMediaType();
                    if (i == transformerCount-1)
                    {
                        if (targetMimetype != null)
                        {
                            throw new IllegalArgumentException("Local pipeline transformer " + name +
                                    " must not specify targetMimetype for the final intermediate transformer, " +
                                    "as this is defined via the supportedSourceAndTargetList."+
                                    " Read from "+readFrom);
                        }
                    }
                    else
                    {
                        if (targetMimetype == null)
                        {
                            throw new IllegalArgumentException("Local pipeline transformer " + name + " must specify " +
                                    "targetMimetype for all intermediate transformers except for the final one."+
                                    " Read from "+readFrom);
                        }
                    }
                    ((LocalPipelineTransformer)localTransformer).addIntermediateTransformer(intermediateTransformer, targetMimetype);
                }
            }
            transformers.put(name, localTransformer);
            super.register(transformer, baseUrl, readFrom);
        }
        catch (IllegalArgumentException e)
        {
            String msg = e.getMessage();
            getLog().error(msg+" Read from "+readFrom);
        }
    }

    @Override
    protected Log getLog()
    {
        return log;
    }

    private List<String> getTEngineUrls()
    {
        List<String> urls = new ArrayList<>();
        for (Object o : getKeySet())
        {
            if (o instanceof String)
            {
                String key = (String)o;
                if (key.startsWith(LOCAL_TRANSFORMER) && key.endsWith(URL))
                {
                    Object url = getProperty(key, null);
                    if (url instanceof String)
                    {
                        String urlStr = ((String)url).trim();
                        if (!urlStr.isEmpty())
                        {
                            urls.add((String) url);
                        }
                    }
                }
            }
        }

        return urls;
    }

    private int getStartupRetryPeriodSeconds(String name)
    {
        String startupRetryPeriodSecondsName = LOCAL_TRANSFORMER + name + ".startupRetryPeriodSeconds";
        String property = getProperty(startupRetryPeriodSecondsName, "60");
        int startupRetryPeriodSeconds;
        try
        {
            startupRetryPeriodSeconds = Integer.parseInt(property);
        }
        catch (NumberFormatException e)
        {
            throw new IllegalArgumentException("Local transformer property " + startupRetryPeriodSecondsName +
                    " should be an integer");
        }
        return startupRetryPeriodSeconds;
    }

    private Map<String, Set<String>> getStrictMimetypeExceptions()
    {
        Map<String, Set<String>> strictMimetypeExceptions = new HashMap<>();

        String whitelist = getProperty(STRICT_MIMETYPE_CHECK_WHITELIST_MIMETYPES, "").trim();
        if (!whitelist.isEmpty())
        {
            String[] mimetypes = whitelist.split(";");

            if (mimetypes.length % 2 != 0)
            {
                getLog().error(STRICT_MIMETYPE_CHECK_WHITELIST_MIMETYPES+" should have an even number of mimetypes as a ; separated list.");
            }
            else
            {
                Set<String> detectedMimetypes = null;
                for (String mimetype: mimetypes)
                {
                    mimetype = mimetype.trim();
                    if (mimetype.isEmpty())
                    {
                        getLog().error(STRICT_MIMETYPE_CHECK_WHITELIST_MIMETYPES+" contains a blank mimetype.");
                        // Still okay to use it in the map though, but it will be ignored.
                    }

                    if (detectedMimetypes == null)
                    {
                        detectedMimetypes = strictMimetypeExceptions.get(mimetype);
                        if (detectedMimetypes == null)
                        {
                            detectedMimetypes = new HashSet<>();
                            strictMimetypeExceptions.put(mimetype, detectedMimetypes);
                        }
                    }
                    else
                    {
                        detectedMimetypes.add(mimetype);
                        detectedMimetypes = null;
                    }
                }
            }
        }

        return strictMimetypeExceptions;
    }

    /**
     * @return the set of property keys and System keys.
     */
    private Set<String> getKeySet()
    {
        Set<Object> systemKeys = System.getProperties().keySet();
        Set<Object> alfrescoGlobalKeys = this.properties.keySet();
        Set<String> keys = new HashSet<>(systemKeys.size()+alfrescoGlobalKeys.size());
        addStrings(keys, systemKeys);
        addStrings(keys, alfrescoGlobalKeys);
        return keys;
    }

    private void addStrings(Set<String> setOfStrings, Set<Object> objects)
    {
        objects.forEach(object->{
            if (object instanceof String)
            {
                setOfStrings.add((String)object);
            }
        });
    }

    /**
     * Gets a property from an alfresco global property but falls back to a System property with the same name to
     * allow dynamic creation of transformers without having to have an AMP to add the alfresco global property.
     */
    private String getProperty(String name, String defaultValue)
    {
        String value = properties.getProperty(name);
        if (value == null || value.isEmpty())
        {
            value = System.getProperty(name);
            if (value != null && value.isEmpty())
            {
                value = null;
            }
        }
        return value == null ? defaultValue : value;
    }

    @Override
    public long getMaxSize(String sourceMimetype, String targetMimetype, Map<String, String> options, String renditionName)
    {
        // This message is not logged if placed in afterPropertiesSet
        if (firstTime)
        {
            firstTime = false;
            transformerDebug.debug("Local transforms "+getCounts()+" are " + (enabled ? "enabled" : "disabled"));
        }

        return enabled
                ? super.getMaxSize(sourceMimetype, targetMimetype, options, renditionName)
                : 0;
    }

    public void transform(ContentReader reader, ContentWriter writer, Map<String, String> actualOptions,
                          String renditionName, NodeRef sourceNodeRef) throws Exception
    {

        String sourceMimetype = reader.getMimetype();
        String targetMimetype = writer.getMimetype();
        long sourceSizeInBytes = reader.getSize();
        LocalTransformer localTransformer = getLocalTransformer(actualOptions, renditionName, sourceMimetype, targetMimetype, sourceSizeInBytes);
        localTransformer.transform(reader, writer, actualOptions, renditionName, sourceNodeRef);
    }

    public LocalTransformer getLocalTransformer(Map<String, String> actualOptions, String renditionName,
                                                String sourceMimetype, String targetMimetype, long sourceSizeInBytes)
    {
        String name = getTransformerName(sourceMimetype, sourceSizeInBytes, targetMimetype, actualOptions, renditionName);
        return transformers.get(name);
    }
}