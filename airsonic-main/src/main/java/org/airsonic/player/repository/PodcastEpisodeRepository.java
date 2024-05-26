package org.airsonic.player.repository;


import org.airsonic.player.domain.PodcastChannel;
import org.airsonic.player.domain.PodcastEpisode;
import org.airsonic.player.domain.PodcastStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PodcastEpisodeRepository extends JpaRepository<PodcastEpisode, Integer> {

    public Optional<PodcastEpisode> findByChannelAndUrl(PodcastChannel channel, String url);

    public Optional<PodcastEpisode> findByChannelAndEpisodeGuid(PodcastChannel channel, String episodeGuid);

    public Optional<PodcastEpisode> findByChannelAndTitleAndPublishDate(PodcastChannel channel, String title,
            Instant publishDate);

    public List<PodcastEpisode> findByChannel(PodcastChannel channel);

    public List<PodcastEpisode> findByChannelAndLockedFalse(PodcastChannel channel);

    public List<PodcastEpisode> findByChannelAndStatus(PodcastChannel channel, PodcastStatus status);

    public List<PodcastEpisode> findByStatusAndPublishDateNotNullAndMediaFilePresentTrueOrderByPublishDateDesc(
                    PodcastStatus status);

}
