package com.wejuai.accounts.service;

import com.endofmaster.commons.util.DateUtil;
import com.endofmaster.rest.exception.BadRequestException;
import com.endofmaster.rest.exception.ServerException;
import com.endofmaster.weixin.support.WxDecryptionUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.wejuai.accounts.repository.*;
import com.wejuai.accounts.web.dto.request.UpdateUserInfoRequest;
import com.wejuai.accounts.web.dto.request.UpdateWxPhoneRequest;
import com.wejuai.accounts.web.dto.request.UpdateWxUserInfoRequest;
import com.wejuai.accounts.web.dto.response.OtherAccountsInfo;
import com.wejuai.accounts.web.dto.response.UserDetailedInfo;
import com.wejuai.accounts.web.dto.response.UserMsgInfo;
import com.wejuai.accounts.web.dto.response.UserNoticeNumInfo;
import com.wejuai.dto.response.UserBaseInfo;
import com.wejuai.entity.mysql.*;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static com.wejuai.accounts.config.Constant.MAPPER;

/**
 * @author ZM.Wang
 */
@Service
public class UserService {

    private final static Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final StarRepository starRepository;
    private final ImageRepository imageRepository;
    private final TitleRepository titleRepository;
    private final RemindRepository remindRepository;
    private final ArticleRepository articleRepository;
    private final CommentRepository commentRepository;
    private final AccountsRepository accountsRepository;
    private final AttentionRepository attentionRepository;
    private final CollectionRepository collectionRepository;
    private final SubCommentRepository subCommentRepository;
    private final WeixinUserRepository weixinUserRepository;
    private final ArticleDraftRepository articleDraftRepository;
    private final RewardDemandRepository rewardDemandRepository;
    private final SystemMessageRepository systemMessageRepository;
    private final CancelAccountRepository cancelAccountRepository;
    private final ChatUserRecordRepository chatUserRecordRepository;

    public UserService(UserRepository userRepository, ImageRepository imageRepository, StarRepository starRepository, CollectionRepository collectionRepository, AttentionRepository attentionRepository, TitleRepository titleRepository, AccountsRepository accountsRepository, CommentRepository commentRepository, SubCommentRepository subCommentRepository, SystemMessageRepository systemMessageRepository, RemindRepository remindRepository, WeixinUserRepository weixinUserRepository, ArticleDraftRepository articleDraftRepository, ArticleRepository articleRepository, RewardDemandRepository rewardDemandRepository, ChatUserRecordRepository chatUserRecordRepository, CancelAccountRepository cancelAccountRepository) {
        this.userRepository = userRepository;
        this.imageRepository = imageRepository;
        this.starRepository = starRepository;
        this.collectionRepository = collectionRepository;
        this.attentionRepository = attentionRepository;
        this.titleRepository = titleRepository;
        this.accountsRepository = accountsRepository;
        this.commentRepository = commentRepository;
        this.subCommentRepository = subCommentRepository;
        this.systemMessageRepository = systemMessageRepository;
        this.remindRepository = remindRepository;
        this.weixinUserRepository = weixinUserRepository;
        this.articleDraftRepository = articleDraftRepository;
        this.articleRepository = articleRepository;
        this.rewardDemandRepository = rewardDemandRepository;
        this.chatUserRecordRepository = chatUserRecordRepository;
        this.cancelAccountRepository = cancelAccountRepository;
    }

    public void updateInfo(User user, UpdateUserInfoRequest request) {
        LocalDate birthday = null;
        if (request.getBirthday() > 0) {
            if (System.currentTimeMillis() < request.getBirthday()) {
                throw new BadRequestException("??????????????????????????????~");
            }
            birthday = DateUtil.date2LocalDate(new Date(request.getBirthday()));
        }
        userRepository.save(user.updateInfo(request.getNickName(), birthday, request.getSex(),
                request.getInShort(), request.getLocation()));
    }

    public void updateHeadImage(User user, String imageId) throws InterruptedException {
        Image avatar = getUploadImage(imageId);
        if (avatar.getType() != ImageUploadType.HEAD_IMAGE) {
            throw new BadRequestException("??????????????????: " + avatar.getType());
        }
        userRepository.save(user.setHeadImage(avatar));
    }

    public void updateCover(User user, String imageId) throws InterruptedException {
        Image avatar = getUploadImage(imageId);
        if (avatar.getType() != ImageUploadType.COVER) {
            throw new BadRequestException("??????????????????: " + avatar.getType());
        }
        userRepository.save(user.setCover(avatar));
    }

    public List<OtherAccountsInfo> getOtherAccountsInfo(User user) {
        Accounts accounts = user.getAccounts();
        List<OtherUser> otherUsers = accounts.getOtherUsers();
        List<OtherAccountsInfo> otherAccountsInfos = new ArrayList<>();
        for (OauthType oauthType : OauthType.values()) {
            for (OtherUser otherUser : otherUsers) {
                OtherAccountsInfo userInfo;
                if (otherUser == null) {
                    userInfo = new OtherAccountsInfo(oauthType);
                } else {
                    userInfo = new OtherAccountsInfo(otherUser);
                }
                if (!otherAccountsInfos.contains(userInfo)) {
                    otherAccountsInfos.add(userInfo);
                }
            }
        }
        return otherAccountsInfos;
    }

    public UserDetailedInfo getUserDetailedInfo(User user) {
        long starNum = starRepository.countByCreateUserId(user.getId());
        long beCollectNum = collectionRepository.countByCreateUserId(user.getId());
        long collectNum = collectionRepository.countByUserId(user.getId());
        long attentionNum = attentionRepository.countByAttention(user);
        long followNum = attentionRepository.countByFollow(user);
        long integral = user.getIntegral();
        long titleNum = titleRepository.countByUser(user);
        long article = articleRepository.countByUserAndDelFalseAndAuthorDelFalse(user);
        long articleDraft = articleDraftRepository.countByUserAndDelFalse(user);
        long rewardDemand = rewardDemandRepository.countByUserAndDelFalse(user);
        return new UserDetailedInfo(starNum, collectNum, beCollectNum, attentionNum, followNum, integral, titleNum, article, articleDraft, rewardDemand);
    }

    public void follow(User user, String followUserId) {
        if (StringUtils.equals(user.getId(), followUserId)) {
            throw new BadRequestException("??????????????????~");
        }
        User followUser = getUser(followUserId);
        if (!attentionRepository.existsByAttentionAndFollow(user, followUser)) {
            attentionRepository.save(new Attention(user, followUser));
        }
    }

    public void unFollow(User user, String followUserId) {
        User followUser = getUser(followUserId);
        Attention attention = attentionRepository.findByAttentionAndFollow(user, followUser);
        if (attention != null) {
            attentionRepository.delete(attention);
        }
    }

    /** ?????????????????? */
    public Page<UserBaseInfo> attentionPage(User user, String titleStr, Pageable pageable) {
        Page<Attention> attentions;
        if (StringUtils.isBlank(titleStr)) {
            attentions = attentionRepository.findByAttention(user, pageable);
        } else {
            attentions = attentionRepository.findByAttentionAndFollow_NickNameLike(user, "%" + titleStr + "%", pageable);
        }
        return attentions.map(attention -> new UserBaseInfo(attention.getFollow()));
    }

    /** ?????????????????? */
    public Page<UserBaseInfo> followPage(User user, Pageable pageable) {
        Page<Attention> follows = attentionRepository.findByFollow(user, pageable);
        return follows.map(attention -> new UserBaseInfo(attention.getAttention()));
    }

    /** ????????????????????? */
    public void unBindOtherUser(User user, OauthType type) {
        Accounts accounts = user.getAccounts();
        if (type == OauthType.QQ) {
            accountsRepository.save(accounts.setQqUser(null));
        } else if (type == OauthType.WEIBO) {
            accountsRepository.save(accounts.setWeiboUser(null));
        } else if (type == OauthType.WEIXIN) {
            accountsRepository.save(accounts.setWeixinUser(null));
        } else {
            throw new BadRequestException("??????????????????????????????");
        }
    }

    public UserNoticeNumInfo getUserNoticeNumInfo(User user) {
        long comment = commentRepository.countByAppCreatorAndWatchFalse(user.getId());
        long sumComment = subCommentRepository.countByAppCreatorOrRecipientAndWatchFalse(user.getId(), user.getId());
        long remind = remindRepository.countByRecipientAndWatchFalse(user.getId());
        long systemMessage = systemMessageRepository.countByUserIdAndWatchFalse(user.getId());
        return new UserNoticeNumInfo(comment, sumComment, remind, systemMessage);
    }

    public User getUser(String id) {
        Optional<User> userOptional = userRepository.findById(id);
        if (userOptional.isEmpty()) {
            throw new BadRequestException("??????????????????" + id);
        }
        return userOptional.get();
    }

    @Transactional
    public void updateWxUserInfo(User user, UpdateWxUserInfoRequest request) {
        WeixinUser weixinUser = user.getAccounts().getWeixinUser();
        weixinUserRepository.save(weixinUser.update(request.getProvince(), request.getCity(), request.getCountry(),
                Sex.find(request.getSex()), request.getNickName(), request.getAvatar()));
        if (StringUtils.equals("????????????", user.getNickName())) {
            userRepository.save(user.updateNickName(request.getNickName()));
        }
    }

    public void updateWxPhone(User user, UpdateWxPhoneRequest request, String sessionKey) {
        try {
            logger.debug("???????????????????????????,  EncryptedData: {}, iv: {}, sessionKey: {}", request.getEncryptedData(), request.getIv(), sessionKey);
            String dataJson = WxDecryptionUtils.decryption(request.getEncryptedData(), request.getIv(), sessionKey);
            logger.debug("?????????????????????????????????" + dataJson);
            JsonNode jsonNode = MAPPER.readTree(dataJson);
            String phone = jsonNode.get("purePhoneNumber").asText();
            accountsRepository.save(user.getAccounts().setPhone(phone));
        } catch (NoSuchAlgorithmException | BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException | InvalidKeyException | NoSuchPaddingException | IllegalArgumentException | IOException e) {
            logger.error("?????????????????????????????????", e);
            throw new ServerException("???????????????", e);
        }
    }

    @Transactional
    public UserMsgInfo getUserMsgInfo(User user) {
        long msgNum = chatUserRecordRepository.sumUserUnreadMsg(user.getId());
        long sysMsgNum = systemMessageRepository.countByUserIdAndWatchFalse(user.getId());
        if (msgNum + sysMsgNum != user.getMsgNum()) {
            userRepository.save(user.setMsgNum(msgNum + sysMsgNum));
        }
        return new UserMsgInfo(msgNum, sysMsgNum);
    }

    public void updatePerformance(User user, Performance performance) {
        userRepository.save(user.setPerformance(performance));
    }

    public void applyCancelAccount(User user, String reason) {
        cancelAccountRepository.save(new CancelAccount(user, reason));
    }

    private Image getUploadImage(String imageId) throws InterruptedException {
        Optional<Image> imageOptional = imageRepository.findById(imageId);
        if (imageOptional.isEmpty()) {
            Thread.sleep(500);
            Optional<Image> imageOptional2 = imageRepository.findById(imageId);
            if (imageOptional2.isEmpty()) {
                throw new BadRequestException("???????????????");
            }
            return imageOptional2.get();
        } else {
            return imageOptional.get();
        }
    }

}
