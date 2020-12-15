/**
 *  Copyright 2020 Martynas Jusevičius <martynas@atomgraph.com>
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package com.atomgraph.linkeddatahub.server.filter.request;

import com.atomgraph.core.vocabulary.SD;
import com.atomgraph.linkeddatahub.apps.model.Application;
import com.atomgraph.linkeddatahub.apps.model.EndUserApplication;
import com.atomgraph.linkeddatahub.client.SesameProtocolClient;
import com.atomgraph.linkeddatahub.exception.auth.AuthorizationException;
import com.atomgraph.linkeddatahub.model.Agent;
import com.atomgraph.linkeddatahub.model.Service;
import com.atomgraph.linkeddatahub.server.security.AgentContext;
import com.atomgraph.linkeddatahub.vocabulary.ACL;
import com.atomgraph.linkeddatahub.vocabulary.LACL;
import com.atomgraph.processor.vocabulary.LDT;
import com.atomgraph.spinrdf.vocabulary.SPIN;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import javax.annotation.PostConstruct;
import javax.annotation.Priority;
import javax.inject.Inject;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.Priorities;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.PreMatching;
import javax.ws.rs.core.Response;
import org.apache.jena.query.ParameterizedSparqlString;
import org.apache.jena.query.QuerySolutionMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDFS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Martynas Jusevičius <martynas@atomgraph.com>
 */
@PreMatching
@Priority(Priorities.USER + 100) // has to execute after all AuthenticationFilters
public class AuthorizationFilter implements ContainerRequestFilter
{
    private static final Logger log = LoggerFactory.getLogger(AuthorizationFilter.class);

    @Inject com.atomgraph.linkeddatahub.Application system;
    @Inject com.atomgraph.linkeddatahub.apps.model.Application app;
    
    private ParameterizedSparqlString authQuery, ownerAuthQuery;

    @PostConstruct
    public void init()
    {
        authQuery = new ParameterizedSparqlString(getSystem().getAuthQuery().toString());
        ownerAuthQuery = new ParameterizedSparqlString(getSystem().getOwnerAuthQuery().toString());
    }
    
    @Override
    public void filter(ContainerRequestContext request) throws IOException
    {
        if (request == null) throw new IllegalArgumentException("ContainerRequestContext cannot be null");
        if (log.isDebugEnabled()) log.debug("Authorizing request URI: {}", request.getUriInfo().getRequestUri());

        // skip filter if no application has matched
        if (getApplication() == null) return;
            
        Resource accessMode = null;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.GET) || request.getMethod().equalsIgnoreCase(HttpMethod.HEAD)) accessMode = ACL.Read;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.POST)) accessMode = ACL.Append;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.PUT)) accessMode = ACL.Write;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.DELETE)) accessMode = ACL.Write;
        if (request.getMethod().equalsIgnoreCase(HttpMethod.PATCH)) accessMode = ACL.Write;
        if (log.isDebugEnabled()) log.debug("Request method: {} ACL access mode: {}", request.getMethod(), accessMode);
        if (accessMode == null)
        {
            if (log.isWarnEnabled()) log.warn("Skipping authentication/authorization, request method not recognized: {}", request.getMethod());
            return;
        }

        final Resource agent;
        if (request.getSecurityContext().getUserPrincipal() instanceof Agent) agent = ((Agent)(request.getSecurityContext().getUserPrincipal()));
        else agent = null; // public access

        Resource authorization = authorize(getApplication(), request, agent, accessMode);
        if (authorization == null)
        {
            if (log.isTraceEnabled()) log.trace("Access not authorized for request URI: {} and access mode: {}", request.getUriInfo().getAbsolutePath(), accessMode);
            throw new AuthorizationException("Access not authorized for request URI", request.getUriInfo().getAbsolutePath(), accessMode);
        }
        else // authorization successful
            if (request.getSecurityContext().getUserPrincipal() instanceof Agent)
                ((AgentContext)request.getSecurityContext()).getAgent().getModel().add(authorization.getModel());
    }
    
    public QuerySolutionMap getAuthorizationParams(com.atomgraph.linkeddatahub.apps.model.Application app, Resource absolutePath, Resource agent, Resource accessMode)
    {
        QuerySolutionMap qsm = new QuerySolutionMap();
        if (app.canAs(EndUserApplication.class)) qsm.add(SD.endpoint.getLocalName(), app.getService().getSPARQLEndpoint()); // needed for federation with the end-user endpoint
        qsm.add(SPIN.THIS_VAR_NAME, absolutePath);
        qsm.add("Mode", accessMode);
        qsm.add(LDT.Ontology.getLocalName(), app.getOntology());
        
        if (agent != null)
        {
            qsm.add("AuthenticatedAgentClass", ACL.AuthenticatedAgent); // enable AuthenticatedAgent UNION branch
            qsm.add("agent", agent);
        }
        else
        {
            qsm.add("AuthenticatedAgentClass", RDFS.Resource); // disable AuthenticatedAgent UNION branch
            qsm.add("agent", RDFS.Resource); // non-matching value that disables the branch of UNION with ?agent
        }
        
        return qsm;
    }
    
    public Resource authorize(com.atomgraph.linkeddatahub.apps.model.Application app, ContainerRequestContext request, Resource agent, Resource accessMode)
    {
        return authorize(app, getAuthorizationParams(app, ResourceFactory.createResource(request.getUriInfo().getAbsolutePath().toString()), agent, accessMode));
    }
    
    public Resource authorize(com.atomgraph.linkeddatahub.apps.model.Application app, QuerySolutionMap qsm)
    {
        final ParameterizedSparqlString pss = app.canAs(EndUserApplication.class) ? getAuthQuery() : getOwnerAuthQuery();
        pss.setParams(qsm); // apply variable bindings to the query string
        Model authModel = loadAuth(qsm, app);
        
        // type check will not work on LACL subclasses without InfModel
        Resource authorization = getResourceByPropertyValue(authModel, ACL.mode, null);
        if (authorization == null) authorization = getResourceByPropertyValue(authModel, ResourceFactory.createProperty(LACL.NS + "accessProperty"), null); // creator access
            
        return authorization;
    }

    protected Model loadAuth(QuerySolutionMap qsm, com.atomgraph.linkeddatahub.apps.model.Application app)
    {
        if (app == null) throw new IllegalArgumentException("Application Resource cannot be null");

        ParameterizedSparqlString pss;
        if (app.canAs(EndUserApplication.class)) pss = getAuthQuery(); // end-user
        else pss = getOwnerAuthQuery(); // admin
        
        Service adminService; // always run auth queries on admin Service
        if (app.canAs(EndUserApplication.class))
        {
            EndUserApplication endUserApp = app.as(EndUserApplication.class);
            adminService = endUserApp.getAdminApplication().getService();

            // set ?endpoint value, otherwise the federation between admin and end-user services will fail
            if (app.getService().canAs(com.atomgraph.linkeddatahub.model.dydra.Service.class))
            {
                Resource repository = app.getService().as(com.atomgraph.linkeddatahub.model.dydra.Service.class).getRepository();
                URI endpointURI = URI.create(repository.getURI());

                // rewrite end-user SERVICE ?endpoint's external URL into "localhost" URL if its host matches base (admin) endpoint's host
                if (adminService.canAs(Service.class))
                {
                    URI adminEndpointURI = URI.create(adminService.as(com.atomgraph.linkeddatahub.model.dydra.Service.class).getRepository().getURI());
                    if (adminEndpointURI.getHost().equals(endpointURI.getHost()))
                        try
                        {
                            endpointURI = new URI(endpointURI.getScheme(), "localhost",
                                    endpointURI.getPath(), endpointURI.getFragment());
                        }
                        catch (URISyntaxException ex)
                        {
                        }
                }

                pss.setIri(SD.endpoint.getLocalName(), endpointURI.toString());
            }
            else
                pss.setIri(SD.endpoint.getLocalName(), app.getService().getSPARQLEndpoint().getURI());
        }
        else
            adminService = app.getService();
        
        return loadModel(pss, qsm, adminService);
    }
    
    /**
     * Loads authorization graph from the admin service.
     * 
     * @param pss auth query string
     * @param qsm query solution map (applied to the query string or sent as request params, depending on the protocol)
     * @param service SPARQL service
     * @return authorization graph (can be empty)
     * @see com.atomgraph.linkeddatahub.vocabulary.APLC#authQuery
     */
    protected Model loadModel(ParameterizedSparqlString pss, QuerySolutionMap qsm, com.atomgraph.linkeddatahub.model.Service service)
    {
        if (pss == null) throw new IllegalArgumentException("ParameterizedSparqlString cannot be null");
        if (qsm == null) throw new IllegalArgumentException("QuerySolutionMap cannot be null");
        if (service == null) throw new IllegalArgumentException("Service cannot be null");

        // send query bindings separately from the query if the service supports the Sesame protocol
        if (service.getSPARQLClient() instanceof SesameProtocolClient)
            try (Response cr = ((SesameProtocolClient)service.getSPARQLClient()). // register(new CacheControlFilter(CacheControl.valueOf("no-cache"))). // add Cache-Control: no-cache to request
                query(pss.asQuery(), Model.class, qsm))
            {
                return cr.readEntity(Model.class);
            }
        else
        {
            pss.setParams(qsm);
            try (Response cr = service.getSPARQLClient(). // register(new CacheControlFilter(CacheControl.valueOf("no-cache"))). // add Cache-Control: no-cache to request
                query(pss.asQuery(), Model.class))
            {
                return cr.readEntity(Model.class);
            }
        }
    }
    
    protected Resource getResourceByPropertyValue(Model model, Property property, RDFNode value)
    {
        if (model == null) throw new IllegalArgumentException("Model cannot be null");
        if (property == null) throw new IllegalArgumentException("Property cannot be null");
        
        ResIterator it = model.listSubjectsWithProperty(property, value);
        
        try
        {
            if (it.hasNext()) return it.next();
        }
        finally
        {
            it.close();
        }

        return null;
    }
    
    protected Service getAdminService()
    {
        return getApplication().canAs(EndUserApplication.class) ? getApplication().as(EndUserApplication.class).getAdminApplication().getService() : getApplication().getService();
    }
    
    public Application getApplication()
    {
        return app;
    }

    public com.atomgraph.linkeddatahub.Application getSystem()
    {
        return system;
    }

    public ParameterizedSparqlString getAuthQuery()
    {
        return authQuery.copy();
    }

    public ParameterizedSparqlString getOwnerAuthQuery()
    {
        return ownerAuthQuery.copy();
    }
    
}
