package me.devsaki.hentoid.database;

import android.content.Context;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.paging.DataSource;
import androidx.paging.LivePagedListBuilder;
import androidx.paging.PagedList;

import com.annimon.stream.Optional;
import com.annimon.stream.Stream;
import com.annimon.stream.function.Consumer;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import io.objectbox.BoxStore;
import io.objectbox.android.ObjectBoxDataSource;
import io.objectbox.android.ObjectBoxLiveData;
import io.objectbox.query.Query;
import io.objectbox.relation.ToOne;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.Chapter;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ErrorRecord;
import me.devsaki.hentoid.database.domains.Group;
import me.devsaki.hentoid.database.domains.GroupItem;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.database.domains.SiteBookmark;
import me.devsaki.hentoid.database.domains.SiteHistory;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Grouping;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.util.ContentHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.widget.ContentSearchManager;
import timber.log.Timber;

public class ObjectBoxDAO implements CollectionDAO {

    private final ObjectBoxDB db;

    ObjectBoxDAO(ObjectBoxDB db) {
        this.db = db;
    }

    public ObjectBoxDAO(Context ctx) {
        db = ObjectBoxDB.getInstance(ctx);
    }

    // Use for testing (store generated by the test framework)
    public ObjectBoxDAO(BoxStore store) {
        db = ObjectBoxDB.getInstance(store);
    }


    public void cleanup() {
        db.closeThreadResources();
    }

    @Override
    public long getDbSizeBytes() {
        return db.getDbSizeBytes();
    }

    @Override
    public List<Content> selectStoredContent(boolean nonFavouritesOnly, boolean includeQueued, int orderField, boolean orderDesc) {
        return db.selectStoredContentQ(nonFavouritesOnly, includeQueued, orderField, orderDesc).build().find();
    }

    @Override
    public List<Long> selectStoredContentIds(boolean nonFavouritesOnly, boolean includeQueued, int orderField, boolean orderDesc) {
        return Helper.getListFromPrimitiveArray(db.selectStoredContentQ(nonFavouritesOnly, includeQueued, orderField, orderDesc).build().findIds());
    }

    @Override
    public long countStoredContent(boolean nonFavouritesOnly, boolean includeQueued) {
        return db.selectStoredContentQ(nonFavouritesOnly, includeQueued, -1, false).build().count();
    }

    @Override
    public long countContentWithUnhashedCovers() {
        return db.selectNonHashedContent().count();
    }

    @Override
    public List<Content> selectContentWithUnhashedCovers() {
        return db.selectNonHashedContent().find();
    }

    @Override
    public void streamStoredContent(boolean nonFavouritesOnly, boolean includeQueued, int orderField, boolean orderDesc, Consumer<Content> consumer) {
        Query<Content> query = db.selectStoredContentQ(nonFavouritesOnly, includeQueued, orderField, orderDesc).build();
        query.forEach(consumer::accept);
    }

    @Override
    public Single<List<Long>> selectRecentBookIds(ContentSearchManager.ContentSearchBundle searchBundle) {
        return Single.fromCallable(() -> contentIdSearch(false, searchBundle, Collections.emptyList()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIds(ContentSearchManager.ContentSearchBundle searchBundle, List<Attribute> metadata) {
        return Single.fromCallable(() -> contentIdSearch(false, searchBundle, metadata))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<List<Long>> searchBookIdsUniversal(ContentSearchManager.ContentSearchBundle searchBundle) {
        return
                Single.fromCallable(() -> contentIdSearch(true, searchBundle, Collections.emptyList()))
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<AttributeQueryResult> selectAttributeMasterDataPaged(
            @NonNull List<AttributeType> types,
            String filter,
            List<Attribute> attrs,
            int page,
            int booksPerPage,
            int orderStyle) {
        return Single
                .fromCallable(() -> pagedAttributeSearch(types, filter, attrs, orderStyle, page, booksPerPage))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public Single<SparseIntArray> countAttributesPerType(List<Attribute> filter) {
        return Single.fromCallable(() -> count(filter))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    @Override
    public List<Chapter> selectChapters(long contentId) {
        return db.selectChapters(contentId);
    }

    public LiveData<List<Content>> selectErrorContent() {
        return new ObjectBoxLiveData<>(db.selectErrorContentQ());
    }

    public List<Content> selectErrorContentList() {
        return db.selectErrorContentQ().find();
    }

    public LiveData<Integer> countAllBooks() {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectVisibleContentQ());

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    public LiveData<Integer> countBooks(long groupId, List<Attribute> metadata) {
        // This is not optimal because it fetches all the content and returns its size only
        // That's because ObjectBox v2.4.0 does not allow watching Query.count or Query.findLazy using LiveData, but only Query.find
        // See https://github.com/objectbox/objectbox-java/issues/776
        ContentSearchManager.ContentSearchBundle bundle = new ContentSearchManager.ContentSearchBundle();
        bundle.setGroupId(groupId);
        bundle.setSortField(Preferences.Constant.ORDER_FIELD_NONE);
        ObjectBoxLiveData<Content> livedata = new ObjectBoxLiveData<>(db.selectContentSearchContentQ(bundle, metadata));

        MediatorLiveData<Integer> result = new MediatorLiveData<>();
        result.addSource(livedata, v -> result.setValue(v.size()));
        return result;
    }

    @Override
    public LiveData<PagedList<Content>> selectRecentBooks(ContentSearchManager.ContentSearchBundle searchBundle) {
        return getPagedContent(false, searchBundle, Collections.emptyList());
    }

    @Override
    public LiveData<PagedList<Content>> searchBooks(ContentSearchManager.ContentSearchBundle searchBundle, List<Attribute> metadata) {
        return getPagedContent(false, searchBundle, metadata);
    }

    @Override
    public LiveData<PagedList<Content>> searchBooksUniversal(ContentSearchManager.ContentSearchBundle searchBundle) {
        return getPagedContent(true, searchBundle, Collections.emptyList());
    }

    public LiveData<PagedList<Content>> selectNoContent() {
        return new LivePagedListBuilder<>(new ObjectBoxDataSource.Factory<>(db.selectNoContentQ()), 1).build();
    }


    private LiveData<PagedList<Content>> getPagedContent(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        boolean isCustomOrder = (searchBundle.getSortField() == Preferences.Constant.ORDER_FIELD_CUSTOM);

        ImmutablePair<Long, DataSource.Factory<Integer, Content>> contentRetrieval;
        if (isCustomOrder)
            contentRetrieval = getPagedContentByList(isUniversal, searchBundle, metadata);
        else
            contentRetrieval = getPagedContentByQuery(isUniversal, searchBundle, metadata);

        int nbPages = Preferences.getContentPageQuantity();
        int initialLoad = nbPages * 2;
        if (searchBundle.getLoadAll()) {
            // Trump Android's algorithm by setting a number of pages higher that the actual number of results
            // to avoid having a truncated result set (see issue #501)
            initialLoad = (int) Math.ceil(contentRetrieval.left * 1.0 / nbPages) * nbPages;
        }

        PagedList.Config cfg = new PagedList.Config.Builder().setEnablePlaceholders(!searchBundle.getLoadAll()).setInitialLoadSizeHint(initialLoad).setPageSize(nbPages).build();
        return new LivePagedListBuilder<>(contentRetrieval.right, cfg).build();
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByQuery(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        boolean isRandom = (searchBundle.getSortField() == Preferences.Constant.ORDER_FIELD_RANDOM);

        Query<Content> query;
        if (isUniversal) {
            query = db.selectContentUniversalQ(searchBundle);
        } else {
            query = db.selectContentSearchContentQ(searchBundle, metadata);
        }

        if (isRandom) {
            List<Long> shuffledIds = db.getShuffledIds();
            return new ImmutablePair<>(query.count(), new ObjectBoxRandomDataSource.RandomDataSourceFactory<>(query, shuffledIds));
        } else return new ImmutablePair<>(query.count(), new ObjectBoxDataSource.Factory<>(query));
    }

    private ImmutablePair<Long, DataSource.Factory<Integer, Content>> getPagedContentByList(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        long[] ids;

        if (isUniversal) {
            ids = db.selectContentUniversalByGroupItem(searchBundle);
        } else {
            ids = db.selectContentSearchContentByGroupItem(searchBundle, metadata);
        }

        return new ImmutablePair<>((long) ids.length, new ObjectBoxPredeterminedDataSource.PredeterminedDataSourceFactory<>(db::selectContentById, ids));
    }

    @Nullable
    public Content selectContent(long id) {
        return db.selectContentById(id);
    }

    public List<Content> selectContent(long[] id) {
        return db.selectContentById(Helper.getListFromPrimitiveArray(id));
    }

    @Nullable
    public Content selectContentBySourceAndUrl(@NonNull Site site, @NonNull String contentUrl, @NonNull String coverUrl) {
        return db.selectContentBySourceAndUrl(site, contentUrl, Content.getNeutralCoverUrlRoot(coverUrl, site));
    }

    public Set<String> selectAllSourceUrls(@NonNull Site site) {
        return db.selectAllContentUrls(site.getCode());
    }

    @Override
    public List<Content> searchTitlesWith(@NonNull String word, int[] contentStatusCodes) {
        return db.selectContentWithTitle(word, contentStatusCodes);
    }

    @Nullable
    public Content selectContentByStorageUri(@NonNull final String storageUri, boolean onlyFlagged) {
        // Select only the "document" part of the URI, as the "tree" part can vary
        String docPart = storageUri.substring(storageUri.indexOf("/document/"));
        return db.selectContentEndWithStorageUri(docPart, onlyFlagged);
    }

    public long insertContent(@NonNull final Content content) {
        return db.insertContent(content);
    }

    public void updateContentStatus(@NonNull final StatusContent updateFrom, @NonNull final StatusContent updateTo) {
        db.updateContentStatus(updateFrom, updateTo);
    }

    public void deleteContent(@NonNull final Content content) {
        db.deleteContentById(content.getId());
    }

    public List<ErrorRecord> selectErrorRecordByContentId(long contentId) {
        return db.selectErrorRecordByContentId(contentId);
    }

    public void insertErrorRecord(@NonNull final ErrorRecord record) {
        db.insertErrorRecord(record);
    }

    public void deleteErrorRecords(long contentId) {
        db.deleteErrorRecords(contentId);
    }

    public void insertChapters(@NonNull final List<Chapter> chapters) {
        db.insertChapters(chapters);
    }

    public void deleteChapters(@NonNull final Content content) {
        db.deleteChaptersByContentId(content.getId());
    }

    @Override
    public void deleteChapter(@NonNull Chapter chapter) {
        db.deleteChapter(chapter.getId());
    }

    @Override
    public void clearDownloadParams(long contentId) {
        Content c = db.selectContentById(contentId);
        if (null == c) return;

        c.setDownloadParams("");
        db.insertContent(c);

        List<ImageFile> imgs = c.getImageFiles();
        if (null == imgs) return;
        for (ImageFile img : imgs) img.setDownloadParams("");
        db.insertImageFiles(imgs);
    }

    @Override
    public void shuffleContent() {
        db.shuffleContentIds();
    }

    @Override
    public long countAllExternalBooks() {
        return db.selectAllExternalBooksQ().count();
    }

    public long countAllInternalBooks(boolean favsOnly) {
        return db.selectAllInternalBooksQ(favsOnly).count();
    }

    public long countAllQueueBooks() {
        return db.selectAllQueueBooksQ().count();
    }

    public List<Content> selectAllInternalBooks(boolean favsOnly) {
        return db.selectAllInternalBooksQ(favsOnly).find();
    }

    public void streamAllInternalBooks(boolean favsOnly, Consumer<Content> consumer) {
        Query<Content> query = db.selectAllInternalBooksQ(favsOnly);
        query.forEach(consumer::accept);
    }

    @Override
    public void deleteAllExternalBooks() {
        db.deleteContentById(db.selectAllExternalBooksQ().findIds());
        db.cleanupOrphanAttributes();
    }

    @Override
    public List<Group> selectGroups(long[] groupIds) {
        return db.selectGroups(groupIds);
    }

    @Override
    public List<Group> selectGroups(int grouping) {
        return db.selectGroupsQ(grouping, null, 0, false, -1, false, 0).find();
    }

    @Override
    public List<Group> selectGroups(int grouping, int subType) {
        return db.selectGroupsQ(grouping, null, 0, false, subType, false, 0).find();
    }

    @Override
    public LiveData<List<Group>> selectGroupsLive(
            int grouping,
            @Nullable String query,
            int orderField,
            boolean orderDesc,
            int artistGroupVisibility,
            boolean groupFavouritesOnly,
            int filterRating) {
        // Artist / group visibility filter is only relevant when the selected grouping is "By Artist"
        int subType = (grouping == Grouping.ARTIST.getId()) ? artistGroupVisibility : -1;

        LiveData<List<Group>> livedata = new ObjectBoxLiveData<>(db.selectGroupsQ(grouping, query, orderField, orderDesc, subType, groupFavouritesOnly, filterRating));
        LiveData<List<Group>> workingData = livedata;

        // Download date grouping : groups are empty as they are dynamically populated
        //   -> Manually add items inside each of them
        //   -> Manually set a cover for each of them
        if (grouping == Grouping.DL_DATE.getId()) {
            MediatorLiveData<List<Group>> livedata2 = new MediatorLiveData<>();
            livedata2.addSource(livedata, groups -> {
                List<Group> enrichedWithItems = Stream.of(groups).map(g -> enrichGroupWithItemsByDlDate(g, g.propertyMin, g.propertyMax)).toList();
                livedata2.setValue(enrichedWithItems);
            });
            workingData = livedata2;
        }

        // Custom grouping : "Ungrouped" special group is dynamically populated
        // -> Manually add items
        if (grouping == Grouping.CUSTOM.getId()) {
            MediatorLiveData<List<Group>> livedata2 = new MediatorLiveData<>();
            livedata2.addSource(livedata, groups -> {
                List<Group> enrichedWithItems = Stream.of(groups).map(this::enrichUngroupedWithItems).toList();
                livedata2.setValue(enrichedWithItems);
            });
            workingData = livedata2;
        }

        // Order by number of children (ObjectBox can't do that natively)
        if (Preferences.Constant.ORDER_FIELD_CHILDREN == orderField) {
            MediatorLiveData<List<Group>> result = new MediatorLiveData<>();
            result.addSource(workingData, groups -> {
                int sortOrder = orderDesc ? -1 : 1;
                List<Group> orderedByNbChildren = Stream.of(groups).sortBy(g -> g.getItems().size() * sortOrder).toList();
                result.setValue(orderedByNbChildren);
            });
            return result;
        }

        // Order by latest download date of children (ObjectBox can't do that natively)
        if (Preferences.Constant.ORDER_FIELD_DOWNLOAD_PROCESSING_DATE == orderField) {
            MediatorLiveData<List<Group>> result = new MediatorLiveData<>();
            result.addSource(workingData, groups -> {
                int sortOrder = orderDesc ? -1 : 1;
                List<Group> orderedByDlDate = Stream.of(groups).sortBy(g -> getLatestDlDate(g) * sortOrder).toList();
                result.setValue(orderedByDlDate);
            });
            return result;
        }

        return workingData;
    }

    private Group enrichGroupWithItemsByDlDate(@NonNull final Group g, int minDays, int maxDays) {
        List<GroupItem> items = selectGroupItemsByDlDate(g, minDays, maxDays);
        g.setItems(items);
        if (!items.isEmpty()) g.coverContent.setTarget(items.get(0).content.getTarget());

        return g;
    }

    private Group enrichUngroupedWithItems(@NonNull final Group g) {
        if (g.grouping.equals(Grouping.CUSTOM) && 1 == g.subtype) {
            List<GroupItem> items = Stream.of(db.selectUngroupedContentIds()).map(id -> new GroupItem(id, g, -1)).toList();
            g.setItems(items);
//            if (!items.isEmpty()) g.picture.setTarget(items.get(0).content.getTarget().getCover()); Can't query Content here as it is detached
        }
        return g;
    }

    private long getLatestDlDate(@NonNull final Group g) {
        // Manually select all content as g.getContents won't work (unresolved items)
        List<Content> contents = db.selectContentById(g.getContentIds());
        if (contents != null) {
            Optional<Long> maxDlDate = Stream.of(contents).map(Content::getDownloadDate).max(Long::compareTo);
            return maxDlDate.isPresent() ? maxDlDate.get() : 0;
        }
        return 0;
    }

    @Nullable
    public Group selectGroup(long groupId) {
        return db.selectGroup(groupId);
    }

    @Nullable
    public Group selectGroupByName(int grouping, @NonNull final String name) {
        return db.selectGroupByName(grouping, name);
    }

    // Does NOT check name unicity
    public long insertGroup(Group group) {
        // Auto-number max order when not provided
        if (-1 == group.order)
            group.order = db.getMaxGroupOrderFor(group.grouping) + 1;
        return db.insertGroup(group);
    }

    public long countGroupsFor(Grouping grouping) {
        return db.countGroupsFor(grouping);
    }

    public void deleteGroup(long groupId) {
        db.deleteGroup(groupId);
    }

    public void deleteAllGroups(Grouping grouping) {
        db.deleteGroupItemsByGrouping(grouping.getId());
        db.selectGroupsByGroupingQ(grouping.getId()).remove();
    }

    public void flagAllGroups(Grouping grouping) {
        db.flagGroups(db.selectGroupsByGroupingQ(grouping.getId()).find(), true);
    }

    public void deleteAllFlaggedGroups() {
        Query<Group> flaggedGroups = db.selectFlaggedGroupsQ();

        // Delete related GroupItems first
        List<Group> groups = flaggedGroups.find();
        for (Group g : groups) db.deleteGroupItemsByGroup(g.id);

        // Actually delete the Group
        flaggedGroups.remove();
    }

    public long insertGroupItem(GroupItem item) {
        // Auto-number max order when not provided
        if (-1 == item.order)
            item.order = db.getMaxGroupItemOrderFor(item.getGroupId()) + 1;

        // If target group doesn't have a cover, get the corresponding Content's
        ToOne<Content> groupCoverContent = item.group.getTarget().coverContent;
        if (!groupCoverContent.isResolvedAndNotNull())
            groupCoverContent.setAndPutTarget(item.content.getTarget());

        return db.insertGroupItem(item);
    }

    public List<GroupItem> selectGroupItems(long contentId, Grouping grouping) {
        return db.selectGroupItems(contentId, grouping.getId());
    }

    private List<GroupItem> selectGroupItemsByDlDate(@NonNull final Group group, int minDays, int maxDays) {
        List<Content> contentResult = db.selectContentByDlDate(minDays, maxDays);
        return Stream.of(contentResult).map(c -> new GroupItem(c, group, -1)).toList();
    }

    public void deleteGroupItems(@NonNull final List<Long> groupItemIds) {
        // Check if one of the GroupItems to delete is linked to the content that contains the group's cover picture
        List<GroupItem> groupItems = db.selectGroupItems(Helper.getPrimitiveArrayFromList(groupItemIds));
        for (GroupItem gi : groupItems) {
            ToOne<Content> groupCoverContent = gi.group.getTarget().coverContent;
            // If so, remove the cover picture
            if (groupCoverContent.isResolvedAndNotNull() && groupCoverContent.getTargetId() == gi.content.getTargetId())
                gi.group.getTarget().coverContent.setAndPutTarget(null);
        }

        db.deleteGroupItems(Helper.getPrimitiveArrayFromList(groupItemIds));
    }


    public List<Content> selectAllQueueBooks() {
        return db.selectAllQueueBooksQ().find();
    }

    public void flagAllInternalBooks() {
        db.flagContents(db.selectAllInternalBooksQ(false).find(), true);
    }

    public void deleteAllInternalBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllInternalBooksQ(false).findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void deleteAllFlaggedBooks(boolean resetRemainingImagesStatus) {
        db.deleteContentById(db.selectAllFlaggedBooksQ().findIds());

        // Switch status of all remaining images (i.e. from queued books) to SAVED, as we cannot guarantee the files are still there
        if (resetRemainingImagesStatus) {
            long[] remainingContentIds = db.selectAllQueueBooksQ().findIds();
            for (long contentId : remainingContentIds)
                db.updateImageContentStatus(contentId, null, StatusContent.SAVED);
        }
    }

    public void flagAllErrorBooksWithJson() {
        db.flagContents(db.selectAllErrorJsonBooksQ().find(), true);
    }

    public void deleteAllQueuedBooks() {
        Timber.i("Cleaning up queue");
        db.deleteContentById(db.selectAllQueueBooksQ().findIds());
        db.deleteQueue();
    }

    public void insertImageFile(@NonNull ImageFile img) {
        db.insertImageFile(img);
    }

    @Override
    public void insertImageFiles(@NonNull List<ImageFile> imgs) {
        db.insertImageFiles(imgs);
    }

    public void replaceImageList(long contentId, @NonNull final List<ImageFile> newList) {
        db.replaceImageFiles(contentId, newList);
    }

    public void updateImageContentStatus(long contentId, StatusContent updateFrom, @NonNull StatusContent updateTo) {
        db.updateImageContentStatus(contentId, updateFrom, updateTo);
    }

    public void updateImageFileStatusParamsMimeTypeUriSize(@NonNull ImageFile image) {
        db.updateImageFileStatusParamsMimeTypeUriSize(image);
    }

    public void deleteImageFiles(@NonNull List<ImageFile> imgs) {
        // Delete the page
        db.deleteImageFiles(imgs);

        // Lists all relevant content
        List<Long> contents = Stream.of(imgs).filter(i -> i.getContent() != null).map(i -> i.getContent().getTargetId()).distinct().toList();

        // Update the content with its new size
        for (Long contentId : contents) {
            Content content = db.selectContentById(contentId);
            if (content != null) {
                content.computeSize();
                db.insertContent(content);
            }
        }
    }

    @Nullable
    public ImageFile selectImageFile(long id) {
        return db.selectImageFile(id);
    }

    public LiveData<List<ImageFile>> selectDownloadedImagesFromContentLive(long id) {
        return new ObjectBoxLiveData<>(db.selectDownloadedImagesFromContentQ(id));
    }

    @Override
    public List<ImageFile> selectDownloadedImagesFromContent(long id) {
        return db.selectDownloadedImagesFromContentQ(id).find();
    }

    public Map<StatusContent, ImmutablePair<Integer, Long>> countProcessedImagesById(long contentId) {
        return db.countProcessedImagesById(contentId);
    }

    public Map<Site, ImmutablePair<Integer, Long>> selectPrimaryMemoryUsagePerSource() {
        return db.selectPrimaryMemoryUsagePerSource();
    }

    public Map<Site, ImmutablePair<Integer, Long>> selectExternalMemoryUsagePerSource() {
        return db.selectExternalMemoryUsagePerSource();
    }

    public void addContentToQueue(@NonNull final Content content, StatusContent targetImageStatus, int position, long replacedContentId, boolean isQueueActive) {
        if (targetImageStatus != null)
            db.updateImageContentStatus(content.getId(), null, targetImageStatus);

        content.setStatus(StatusContent.DOWNLOADING);
        content.setIsBeingDeleted(false); // Remove any UI animation
        if (replacedContentId > -1) content.setContentIdToReplace(replacedContentId);
        db.insertContent(content);

        if (!db.isContentInQueue(content)) {
            int targetPosition;
            if (position == Preferences.Constant.QUEUE_NEW_DOWNLOADS_POSITION_BOTTOM) {
                targetPosition = (int) db.selectMaxQueueOrder() + 1;
            } else { // Top - don't put #1 if queue is active not to interrupt current download
                targetPosition = (isQueueActive) ? 2 : 1;
            }
            insertQueueAndRenumber(content.getId(), targetPosition);
        }
    }

    private void insertQueueAndRenumber(long contentId, int order) {
        List<QueueRecord> queue = db.selectQueueRecordsQ(null).find();
        QueueRecord newRecord = new QueueRecord(contentId, order);

        // Put in the right place
        if (order > queue.size()) queue.add(newRecord);
        else {
            int newOrder = Math.min(queue.size() + 1, order);
            queue.add(newOrder - 1, newRecord);
        }
        // Renumber everything and save
        int index = 1;
        for (QueueRecord qr : queue) qr.setRank(index++);
        db.updateQueue(queue);
    }

    private List<Long> contentIdSearch(
            boolean isUniversal,
            ContentSearchManager.ContentSearchBundle searchBundle,
            List<Attribute> metadata) {
        if (isUniversal) {
            return Helper.getListFromPrimitiveArray(db.selectContentUniversalId(searchBundle, ContentHelper.getLibraryStatuses()));
        } else {
            return Helper.getListFromPrimitiveArray(db.selectContentSearchId(searchBundle, metadata));
        }
    }

    private AttributeQueryResult pagedAttributeSearch(
            @NonNull List<AttributeType> attrTypes,
            String filter,
            List<Attribute> attrs,
            int sortOrder,
            int pageNum,
            int itemPerPage) {
        AttributeQueryResult result = new AttributeQueryResult();

        if (!attrTypes.isEmpty()) {
            if (attrTypes.get(0).equals(AttributeType.SOURCE)) {
                result.attributes.addAll(db.selectAvailableSources(attrs));
                result.totalSelectedAttributes = result.attributes.size();
            } else {
                for (AttributeType type : attrTypes) {
                    // TODO fix sorting when concatenating both lists
                    result.attributes.addAll(db.selectAvailableAttributes(type, attrs, filter, sortOrder, pageNum, itemPerPage));
                    result.totalSelectedAttributes += db.countAvailableAttributes(type, attrs, filter);
                }
            }
        }

        return result;
    }

    private SparseIntArray count(List<Attribute> filter) {
        SparseIntArray result;

        if (null == filter || filter.isEmpty()) {
            result = db.countAvailableAttributesPerType();
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources().size());
        } else {
            result = db.countAvailableAttributesPerType(filter);
            result.put(AttributeType.SOURCE.getCode(), db.selectAvailableSources(filter).size());
        }

        return result;
    }

    public LiveData<List<QueueRecord>> selectQueueLive() {
        return new ObjectBoxLiveData<>(db.selectQueueRecordsQ(null));
    }

    @Override
    public LiveData<List<QueueRecord>> selectQueueLive(String query) {
        return new ObjectBoxLiveData<>(db.selectQueueRecordsQ(query));
    }

    @Override
    public List<QueueRecord> selectQueue() {
        return db.selectQueueRecordsQ(null).find();
    }

    @Nullable
    public QueueRecord selectQueue(long contentId) {
        return db.selectQueueRecordFromContentId(contentId);
    }

    public void updateQueue(@NonNull List<QueueRecord> queue) {
        db.updateQueue(queue);
    }

    public void deleteQueue(@NonNull Content content) {
        db.deleteQueue(content);
    }

    public void deleteQueue(int index) {
        db.deleteQueue(index);
    }

    public SiteHistory selectHistory(@NonNull Site s) {
        return db.selectHistory(s);
    }

    public void insertSiteHistory(@NonNull Site site, @NonNull String url) {
        db.insertSiteHistory(site, url);
    }

    public long countAllBookmarks() {
        return db.selectBookmarksQ(null).count();
    }

    public List<SiteBookmark> selectAllBookmarks() {
        return db.selectBookmarksQ(null).find();
    }

    public void deleteAllBookmarks() {
        db.selectBookmarksQ(null).remove();
    }

    public List<SiteBookmark> selectBookmarks(@NonNull Site s) {
        return db.selectBookmarksQ(s).find();
    }

    @Override
    public SiteBookmark selectHomepage(@NonNull Site s) {
        return db.selectHomepage(s);
    }

    public long insertBookmark(@NonNull final SiteBookmark bookmark) {
        // Auto-number max order when not provided
        if (-1 == bookmark.getOrder())
            bookmark.setOrder(db.getMaxBookmarkOrderFor(bookmark.getSite()) + 1);
        return db.insertBookmark(bookmark);
    }

    public void insertBookmarks(@NonNull List<SiteBookmark> bookmarks) {
        // Mass insert method; no need to renumber here
        db.insertBookmarks(bookmarks);
    }

    public void deleteBookmark(long bookmarkId) {
        db.deleteBookmark(bookmarkId);
    }


    // ONE-TIME USE QUERIES (MIGRATION & CLEANUP)

    // API29 migration query
    @Override
    public Single<List<Long>> selectOldStoredBookIds() {
        return Single.fromCallable(() -> Helper.getListFromPrimitiveArray(db.selectOldStoredContentQ().findIds()))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    // API29 migration query
    @Override
    public long countOldStoredContent() {
        return db.selectOldStoredContentQ().count();
    }
}
