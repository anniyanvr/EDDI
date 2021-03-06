package ai.labs.resources.impl.config.http.mongo;

import ai.labs.persistence.mongo.HistorizedResourceStore;
import ai.labs.persistence.mongo.MongoResourceStorage;
import ai.labs.resources.rest.config.http.IHttpCallsStore;
import ai.labs.resources.rest.config.http.model.HttpCall;
import ai.labs.resources.rest.config.http.model.HttpCallsConfiguration;
import ai.labs.serialization.IDocumentBuilder;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoDatabase;

import javax.inject.Inject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class HttpCallsStore implements IHttpCallsStore {
    private HistorizedResourceStore<HttpCallsConfiguration> httpCallsResourceStore;

    @Inject
    public HttpCallsStore(MongoDatabase database, IDocumentBuilder documentBuilder) {
        RuntimeUtilities.checkNotNull(database, "database");
        final String collectionName = "httpcalls";
        MongoResourceStorage<HttpCallsConfiguration> resourceStorage =
                new MongoResourceStorage<>(database, collectionName, documentBuilder, HttpCallsConfiguration.class);
        this.httpCallsResourceStore = new HistorizedResourceStore<>(resourceStorage);
    }

    @Override
    public HttpCallsConfiguration readIncludingDeleted(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        return httpCallsResourceStore.readIncludingDeleted(id, version);
    }

    @Override
    public IResourceId create(HttpCallsConfiguration httpCallsConfiguration) throws ResourceStoreException {
        return httpCallsResourceStore.create(httpCallsConfiguration);
    }

    @Override
    public HttpCallsConfiguration read(String id, Integer version)
            throws ResourceNotFoundException, ResourceStoreException {

        return httpCallsResourceStore.read(id, version);
    }

    @Override
    @ConfigurationUpdate
    public Integer update(String id, Integer version, HttpCallsConfiguration httpCallsConfiguration)
            throws ResourceStoreException, ResourceModifiedException, ResourceNotFoundException {

        return httpCallsResourceStore.update(id, version, httpCallsConfiguration);
    }

    @Override
    @ConfigurationUpdate
    public void delete(String id, Integer version) throws ResourceModifiedException, ResourceNotFoundException {
        httpCallsResourceStore.delete(id, version);
    }

    @Override
    public void deleteAllPermanently(String id) {
        httpCallsResourceStore.deleteAllPermanently(id);
    }

    @Override
    public IResourceId getCurrentResourceId(String id) throws ResourceNotFoundException {
        return httpCallsResourceStore.getCurrentResourceId(id);
    }

    @Override
    public List<String> readActions(String id, Integer version, String filter, Integer limit)
            throws ResourceNotFoundException, ResourceStoreException {

        List<String> actions = read(id, version).
                getHttpCalls().stream().
                map(HttpCall::getActions).
                flatMap(Collection::stream).
                collect(Collectors.toList());

        return limit > 0 ? actions.subList(0, limit) : actions;
    }
}
